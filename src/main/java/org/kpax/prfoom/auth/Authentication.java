package org.kpax.prfoom.auth;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.kpax.prfoom.UserConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author Eugen Covaci {@literal eugen.covaci.q@gmail.com}
 * Created on 11/16/2019
 */
@Component
public class Authentication {

    @Autowired
    private UserConfig userConfig;

    private CredentialsProvider credentialsProvider;

    public CredentialsProvider getCredentialsProvider() {
        if (credentialsProvider == null) {
            synchronized (this) {
                if (credentialsProvider == null) {
                    credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(AuthScope.ANY,
                            new NTCredentials(userConfig.getUsername(), userConfig.getPassword(), null, userConfig.getDomain()));
                }
            }
        }
        return credentialsProvider;
    }
}
