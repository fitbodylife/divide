package io.divide.client;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import io.divide.client.auth.AuthManager;
import io.divide.client.cache.LocalStorage;
import io.divide.client.cache.LocalStorageIBoxDb;
import io.divide.client.data.DataManager;
import io.divide.client.data.ObjectManager;
import io.divide.client.push.PushManager;

/**
 * Created by williamwebb on 4/2/14.
 */
class BackendModule extends AbstractModule {
    private BackendConfig config;

    public BackendModule(BackendConfig config){
        this.config = config;
    }

    @Override
    protected void configure() {
        // ORDER MATTER
        bind(BackendConfig.class).toInstance(config);
        bind(Backend.class).in(Singleton.class);

        bind(LocalStorage.class).to(LocalStorageIBoxDb.class).in(Singleton.class);
        bind(AuthManager.class).in(Singleton.class);
        bind(DataManager.class).in(Singleton.class);
        bind(PushManager.class).in(Singleton.class);
        bind(ObjectManager.class).in(Singleton.class);

        requestStaticInjection(Backend.class);
        requestStaticInjection(BackendUser.class);
        requestStaticInjection(BackendServices.class);
    }
}