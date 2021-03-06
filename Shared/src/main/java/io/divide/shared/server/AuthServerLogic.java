/*
 * Copyright (C) 2014 Divide.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divide.shared.server;

import io.divide.shared.transitory.Credentials;
import io.divide.shared.transitory.TransientObject;
import io.divide.shared.transitory.query.OPERAND;
import io.divide.shared.transitory.query.Query;
import io.divide.shared.transitory.query.QueryBuilder;
import io.divide.shared.util.AuthTokenUtils;
import io.divide.shared.util.AuthTokenUtils.AuthenticationException;
import io.divide.shared.util.DaoUtils;
import io.divide.shared.util.ObjectUtils;
import io.divide.shared.util.ReflectionUtils;
import org.apache.http.HttpStatus;
import org.mindrot.jbcrypt.BCrypt;

import java.security.PublicKey;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

import static io.divide.shared.server.DAO.DAOException;
import static io.divide.shared.util.DaoUtils.getUserByEmail;

public class AuthServerLogic<DAOOut extends TransientObject> extends ServerLogic<DAOOut> {

    private static Calendar c = Calendar.getInstance(TimeZone.getDefault());

    private KeyManager keyManager;

    public AuthServerLogic(DAO<TransientObject,DAOOut> dao, KeyManager keyManager) {
        super(dao);
        this.keyManager = keyManager;
    }

    /*
     * Saves user credentials
     */

    public Credentials userSignUp(Credentials credentials) throws DAOException{
        if (getUserByEmail(dao,credentials.getEmailAddress())!=null){
            throw new DAOException(HttpStatus.SC_CONFLICT,"User Already Exists");
        }
        ServerCredentials toSave = new ServerCredentials(credentials);

        toSave.decryptPassword(keyManager.getPrivateKey()); //decrypt the password
        String de = toSave.getPassword();
        String ha = BCrypt.hashpw(de, BCrypt.gensalt(10));

        toSave.setOwnerId(dao.count(Credentials.class.getName()) + 1);
        toSave.setPassword(ha); //hash the password for storage
        toSave.setAuthToken(AuthTokenUtils.getNewToken(keyManager.getSymmetricKey(), toSave));
        toSave.setRecoveryToken(AuthTokenUtils.getNewToken(keyManager.getSymmetricKey(), toSave));

        dao.save(toSave);

        return toSave;
    }

    /**
     * Checks username/password against that stored in DB, if same return
     * token, if token expired create new.
     * @param credentials
     * @return authentication token
     */

    public Credentials userSignIn(Credentials credentials) throws DAOException {
            Credentials dbCreds = getUserByEmail(dao,credentials.getEmailAddress());
            if (dbCreds == null){
                throw new DAOException(HttpStatus.SC_UNAUTHORIZED,"User Doesnt exist");
            }
            else {
                //check if we are resetting the password
                if(dbCreds.getValidation()!=null && dbCreds.getValidation().equals(credentials.getValidation())){
                    credentials.decryptPassword(keyManager.getPrivateKey()); //decrypt the password
                    dbCreds.setPassword(BCrypt.hashpw(credentials.getPassword(), BCrypt.gensalt(10))); //set the new password
                }
                //else check password
                else {
                    String en = credentials.getPassword();
                    credentials.decryptPassword(keyManager.getPrivateKey()); //decrypt the password
                    String de = credentials.getPassword();
                    String ha = BCrypt.hashpw(de, BCrypt.gensalt(10));
                    System.out.println("Comparing passwords.\n" +
                            "Encrypted: " + en + "\n" +
                            "Decrypted: " + de + "\n" +
                            "Hashed:    " + ha + "\n" +
                            "Stored:    " + dbCreds.getPassword());
                    if (!BCrypt.checkpw(de, dbCreds.getPassword())){
                        throw new DAOException(HttpStatus.SC_UNAUTHORIZED,"User Already Exists");
                    }
                }

//              check if token is expired, if so return/set new
                AuthTokenUtils.AuthToken token;
                try {
                    token = new AuthTokenUtils.AuthToken(keyManager.getSymmetricKey(),dbCreds.getAuthToken());
                } catch (AuthenticationException e) {
                    throw new DAOException(HttpStatus.SC_INTERNAL_SERVER_ERROR,"internal error");
                }
                if (c.getTime().getTime() > token.expirationDate) {
                    dbCreds.setAuthToken(AuthTokenUtils.getNewToken(keyManager.getSymmetricKey(), dbCreds));
                    dao.save(dbCreds);
                }

                return dbCreds;
            }
    }

    public byte[] getPublicKey()  {
        PublicKey publicKey = keyManager.getPublicKey();
        return publicKey.getEncoded();
    }

    /**
     * Validate a user account
     * @param token
     */

    public boolean validateAccount(String token) throws DAOException {
            Query q = new QueryBuilder().select().from(Credentials.class).where("validation", OPERAND.EQ, token).build();

            TransientObject to = ObjectUtils.get1stOrNull(dao.query(q));
            if (to != null) {
                ServerCredentials creds = new ServerCredentials(to);
                creds.setValidation("1");
                dao.save(creds);
                return true;
            } else {
                return false;
            }

    }

    public Credentials getUserFromAuthToken(String token) throws DAOException {

        AuthTokenUtils.AuthToken authToken;
        try {
            authToken = new AuthTokenUtils.AuthToken(keyManager.getSymmetricKey(),token);
        } catch (AuthenticationException e) {
            throw new DAOException(HttpStatus.SC_INTERNAL_SERVER_ERROR,"internal error");
        }
        if(authToken.isExpired()) throw new DAOException(HttpStatus.SC_UNAUTHORIZED,"Expired");

        Query q = new QueryBuilder().select().from(Credentials.class).where(Credentials.AUTH_TOKEN_KEY,OPERAND.EQ,token).build();

        TransientObject to = ObjectUtils.get1stOrNull(dao.query(q));
        if(to!=null){
            return new ServerCredentials(to);
        } else {
            throw new DAOException(HttpStatus.SC_BAD_REQUEST,"invalid auth token");
        }
    }

    public Credentials getUserFromRecoveryToken(String token) throws DAOException {
        Query q = new QueryBuilder().select().from(Credentials.class).where(Credentials.RECOVERY_TOKEN_KEY,OPERAND.EQ,token).build();

        TransientObject to = ObjectUtils.get1stOrNull(dao.query(q));
        if(to!=null){
            ServerCredentials sc = new ServerCredentials(to);
            sc.setAuthToken(AuthTokenUtils.getNewToken(keyManager.getSymmetricKey(), sc));
            sc.setRecoveryToken(AuthTokenUtils.getNewToken(keyManager.getSymmetricKey(), sc));
            dao.save(sc);
            return sc;
        } else {
            throw new DAOException(HttpStatus.SC_BAD_REQUEST,"invalid recovery token");
        }
    }

    public void recieveUserData(String userId, Map<String,?> data) throws DAOException {
        Credentials user = getUserById(userId);
        user.removeAll();
        user.putAll(data);
        dao.save(user);
    }

    public Map<String,Object> sendUserData(String userId) {
        return getUserById(userId).getUserData();
    }

    public Credentials getUserById(String id){
        return DaoUtils.getUserById(dao, id);
    }

////    @POST
////    @Path("/reset")
////    @Consumes(MediaType.APPLICATION_JSON)
////    @Produces(MediaType.APPLICATION_JSON)
////    public Response resetAccount(EncryptedEntity encrypted) {
////        try{
////            String email = encrypted.getPlainText(getKeys().getPrivate());
////            Query q = new QueryBuilder().select(null).from(Credentials.class).where("emailAddress", OPERAND.EQ, email).limit(1).build();
////
////            TransientObject to = (TransientObject) ObjectUtils.get1stOrNull(dao.query(q));
////            if (to != null) {
////                ServerCredentials creds = new ServerCredentials(to);
////                creds.setValidation(getNewAuthToken());
////                dao.save(creds);
////
////                EmailMessage emailMessage = new EmailMessage(
////                        "someEmail",
////                        email,
////                        "Tactics Password Reset",
////                        "some link " + creds.getAuthToken());
////
////                sendEmail(emailMessage);
////
////                return Response
////                        .ok()
////                        .build();
////            } else {
////                return Response
////                        .status(Status.NOT_FOUND)
////                        .build();
////            }
////        } catch (NoSuchAlgorithmException e) {
////            return Response.serverError().build();
////        } catch (DAO.DAOException e) {
////            return fromDAOExpection(e);
////        } catch (MessagingException e) {
////            return errorResponse(e);
////        } catch (UnsupportedEncodingException e) {
////            return errorResponse(e);
////        }
////    }

//
//    private static Response errorResponse(Throwable error){
//        return Response.status(Status.INTERNAL_SERVER_ERROR).entity(error).build();
//    }

//    public void sendEmail(EmailMessage emailMessage) throws MessagingException, UnsupportedEncodingException {
//
//        Properties props = new Properties();
//        Session session = Session.getDefaultInstance(props, null);
//
//        Message msg = new MimeMessage(session);
//        msg.setFrom(new InternetAddress(emailMessage.getFrom(), ""));
//        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(emailMessage.getTo(), ""));
//        msg.setSubject(emailMessage.getSubject());
//        msg.setText(emailMessage.getBody());
//        Transport.send(msg);
//
//    }
//
//    public static class EmailMessage {
//        private String from;
//        private String to;
//        private String subject;
//        private String body;
//
//        public EmailMessage(String from, String to, String subject, String body) throws MessagingException {
//            setFrom(from);
//            setTo(to);
//            setSubject(subject);
//            setBody(body);
//        }
//
//        public String getFrom() {
//            return from;
//        }
//
//        public void setFrom(String from) throws MessagingException {
//            if (!validEmail(from)) throw new MessagingException("Invalid email address!");
//            this.from = from;
//        }
//
//        public String getTo() {
//            return to;
//        }
//
//        public void setTo(String to) throws MessagingException {
//            if (!validEmail(to)) throw new MessagingException("Invalid email address!");
//            this.to = to;
//        }
//
//        public String getSubject() {
//            return subject;
//        }
//
//        public void setSubject(String subject) {
//            this.subject = subject;
//        }
//
//        public String getBody() {
//            return body;
//        }
//
//        public void setBody(String body) {
//            this.body = body;
//        }
//
//        private boolean validEmail(String email) {
//            // editing to make requirements listed
//            // return email.matches("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,4}");
//            return email.matches("[A-Z0-9._%+-][A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{3}");
//        }
//    }

    private static class ServerCredentials extends Credentials {

        public ServerCredentials(TransientObject serverObject){
            try {
                Map meta = (Map) ReflectionUtils.getObjectField(serverObject, TransientObject.META_DATA);
                Map user = (Map) ReflectionUtils.getObjectField(serverObject,TransientObject.USER_DATA);

                ReflectionUtils.setObjectField(this, TransientObject.META_DATA, meta);
                ReflectionUtils.setObjectField(this, TransientObject.USER_DATA, user);
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void setOwnerId(Integer id){
            super.setOwnerId(id);
        }

    }
}
