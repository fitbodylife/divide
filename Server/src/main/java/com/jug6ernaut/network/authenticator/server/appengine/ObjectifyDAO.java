package com.jug6ernaut.network.authenticator.server.appengine;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.LoadType;
import com.jug6ernaut.network.authenticator.server.dao.DAO;
import com.jug6ernaut.network.shared.util.ObjectUtils;
import com.jug6ernaut.network.shared.web.transitory.TransientObject;
import com.jug6ernaut.network.shared.web.transitory.query.Clause;
import com.jug6ernaut.network.shared.web.transitory.query.Query;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.jug6ernaut.network.authenticator.server.appengine.OfyService.ofy;

/**
 * Created with IntelliJ IDEA.
 * User: williamwebb
 * Date: 8/5/13
 * Time: 4:50 PM
 */
@Provider
public class ObjectifyDAO implements DAO {
    Logger logger = Logger.getLogger(String.valueOf(ObjectifyDAO.class));

//    @Context
//    EventNotifier eventNotifier;

    @Override
    public <T extends Object> List<T> query(Query query) throws DAOException{
        logger.info("query: " + query);
        LoadType<?> filter = ofy().load().type(OfyObject.class);
        com.googlecode.objectify.cmd.Query<?> oFilter =
        filter.filter(TransientObject.USER_DATA+"."+ TransientObject.OJBECT_TYPE_KEY + " =",query.getFrom());

        for(Clause c : query.getWhere().values()){
            oFilter = oFilter.filter(TransientObject.USER_DATA + "." + c.getBefore() + " " + c.getOperand(), c.getAfter());
        }

        if(query.getOffset()!=null){
            oFilter = oFilter.offset(query.getOffset());
        }
        if(query.getLimit()!=null){
            oFilter = oFilter.limit(query.getLimit());
        }

        List list = null;
        switch (query.getAction()){
            case SELECT:{
                list = oFilter.list();
                List<TransientObject> toReturn = new ArrayList<TransientObject>(list.size());
                try{
                    for (OfyObject oo : (List<OfyObject>)list){
                        logger.info("Got: " + oo);
                        toReturn.add(BackendToOfy.getBack(oo));
                    }
                } catch (Exception e) {
                    throw new DAOException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),e);
                }

                list.clear();
                list = toReturn;
            }break;
            case DELETE:{
                list = oFilter.keys().list();
                ofy().delete().keys(list);
            }break;
            case UPDATE:{
                throw new NotImplementedException();
            }
        }

        logger.info("Returning: " + list);

//        eventNotifier.fire("query",list);

        return (List<T>) list;
    }

    @Override
    public Collection<TransientObject> get(final TransientObject... keys) throws DAOException {
        logger.info("get: " + ObjectUtils.v2c(keys));

        List<Key<OfyObject>> ofyKeys = new ArrayList<Key<OfyObject>>(keys.length);
        for (TransientObject to : keys){
            ofyKeys.add(Key.create(OfyObject.class, to.getObjectKey()));
        }

        Map<Key<OfyObject>, OfyObject> ofyObjets = ofy().load().keys(ofyKeys);

        List<TransientObject> tos = new ArrayList<TransientObject>(ofyObjets.size());
        try{
            for (OfyObject oo : ofyObjets.values()){
                tos.add(BackendToOfy.getBack(oo));
            }
        } catch (Exception e) {
            throw new DAOException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),e);
        }

//        eventNotifier.fire("get",tos);

        return tos;

//        Collection<TransientObject> obs = ofy().transact(new Work<Collection<TransientObject>>() {
//            public Collection<TransientObject> run() {
//                Collection<TransientObject> objects = new ArrayList<TransientObject>();
//
//                for (TransientObject object : keys){
//                    try {
//                        logger.info("Key: " + object.getObjectKey());
//                        //logger.info("Exits: " + exists(object.getObjectKey()));
//
//                        Key key = Key.create(OfyObject.class, object.getObjectKey());
//                        logger.info("Key: " + key);
//
//
//                        objects.add(BackendToOfy.getBack(ofyObject));
//
//                    } catch (NoSuchFieldException e) {
//                        e.printStackTrace();
//                    } catch (IllegalAccessException e) {
//                        e.printStackTrace();
//                    }
//                }
//
//                return objects;
//            }
//        });
//
//        return obs;
    }

    @Override
    public void save(TransientObject... objects) throws DAOException{
        logger.info("save: " + ObjectUtils.v2c(objects));

        try{
            for(TransientObject bo : objects){
                ofy().save().entities(BackendToOfy.getOfy(bo));
            }
        } catch (Exception e) {
            throw new DAOException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),e);
        }
//        eventNotifier.fire("save",objects);
    }

    @Override
    public void delete(TransientObject... objects) throws DAOException {
        logger.info("delete: " + ObjectUtils.v2c(objects));

        try{
            for(TransientObject bo : objects){
                ofy().delete().entities(BackendToOfy.getOfy(bo));
            }
        } catch (Exception e) {
            throw new DAOException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),e);
        }
//        eventNotifier.fire("delete",objects);
    }

    @Override
    public boolean exists(TransientObject... objects) {
        logger.info("exists: " + ObjectUtils.v2c(objects));

        boolean exists = true;
        for(TransientObject bo : objects){
            if(!exists(bo.getObjectKey())) exists = false;
        }
        return exists;
    }

    @Override
    public int count(String objectType) {
        com.googlecode.objectify.cmd.Query<?> query =
                ofy().
                load().
                type(OfyObject.class).
                filter(TransientObject.USER_DATA + "." + TransientObject.OJBECT_TYPE_KEY + " =", objectType);


        return query.count();
    }

    private boolean exists(String key){
        return (OfyService.ofy().load().filterKey(Key.create(OfyObject.class,key)).count() == 1);
    }


}