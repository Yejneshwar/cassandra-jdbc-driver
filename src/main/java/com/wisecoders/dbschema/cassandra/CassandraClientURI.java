package com.wisecoders.dbschema.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import software.amazon.awssdk.utils.StringUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.wisecoders.dbschema.cassandra.JdbcDriver.LOGGER;

/**
 * Copyright Wise Coders GmbH. The Cassandra JDBC driver is build to be used with DbSchema Database Designer https://dbschema.com
 * Free to use by everyone, code modifications allowed only to
 * the public repository https://github.com/wise-coders/cassandra-jdbc-driver
 */

public class CassandraClientURI {

    static final String PREFIX = "jdbc:cassandra://";

    private final List<String> hosts;
    private final String keyspace;
    private final String dataCenter;
    private final String collection;
    private final String uri;
    private final String userName;
    private final String password;
    private final Boolean sslEnabled;
    private final String trustStore;
    private final String trustStorePassword;
    private final String keyStore;
    private final String keyStorePassword;
    private final String awsSecretName;
    private final String awsSecretKey;
    private final String awsRegion;
    private final String configFile;

    public CassandraClientURI(String uri, Properties info) {
        this.uri = uri;
        LOGGER.info("URI: " + maskAllPassowords(uri));
        if (!uri.startsWith(PREFIX))
            throw new IllegalArgumentException("URI needs to start with " + PREFIX);

        uri = uri.substring(PREFIX.length());

        String serverPart;
        String nsPart;
        Map<String, List<String>> options = null;

        {
            int idx;
            if ( ( idx = uri.indexOf("?")) > 0 || ( idx = uri.indexOf(";")) > 0 ){
                options = parseOptions( uri.substring( idx+1));
                uri = uri.substring(0, idx );
            }

            if ( ( idx = uri.indexOf("/")) > 0 ){
                serverPart = uri.substring(0, idx);
                nsPart = uri.substring(idx + 1);
            } else {
                serverPart = uri;
                nsPart = null;
            }
        }

        this.userName = getOption(info, options, "user");

        this.awsRegion = getOption(info, options, "awsregion");
        this.awsSecretName = getOption(info, options, "awssecretname");
        this.awsSecretKey = getOption(info, options, "awssecretkey");
        if (awsRegion != null && awsSecretName != null && awsSecretKey != null) {
            this.password = AWSUtil.getSecretValue(this.awsRegion, this.awsSecretName, this.awsSecretKey);
        } else {
            this.password = getOption(info, options, "password");
        }

        this.dataCenter = getOption(info, options, "dc");
        String sslEnabledOption = getOption(info, options, "sslenabled");
        this.sslEnabled = Boolean.parseBoolean(sslEnabledOption);

        String trustStore = getOption(info, options, "javax.net.ssl.truststore");
        String trustStorePassword = getOption(info, options, "javax.net.ssl.truststorepassword");
        String keyStore = getOption(info, options, "javax.net.ssl.keystore");
        String keyStorePassword = getOption(info, options, "javax.net.ssl.keystorepassword");
        this.trustStore = trustStore == null ? System.getProperty("javax.net.ssl.trustStore") : trustStore;
        this.trustStorePassword = trustStorePassword == null ? System.getProperty("javax.net.ssl.trustStorePassword") : trustStorePassword;
        this.keyStore = keyStore == null ? System.getProperty("javax.net.ssl.keyStore") : keyStore;
        this.keyStorePassword = keyStorePassword == null ? System.getProperty("javax.net.ssl.keyStorePassword") : keyStorePassword;

        this.configFile = getOption(info, options, "configfile");

        { // userName,password,hosts
            List<String> all = new LinkedList<>();
            Collections.addAll(all, serverPart.split(","));
            hosts = Collections.unmodifiableList(all);
        }

        if (nsPart == null || nsPart.length() == 0) {
            keyspace = null;
            collection = null;
        } else { // keyspace._collection
            int dotIndex = nsPart.indexOf(".");
            if (dotIndex < 0) {
                keyspace = nsPart;
                collection = null;
            } else {
                keyspace = nsPart.substring(0, dotIndex);
                collection = nsPart.substring(dotIndex + 1);
            }
        }
        LOGGER.info("hosts=" + hosts + " keyspace=" + keyspace + " collection=" + collection + " user=" + userName + " dc=" + dataCenter + " sslenabled=" + sslEnabledOption );

    }

    public String maskAllPassowords(String uri) {
        StringBuffer uriBuffer = new StringBuffer(uri);
        Pattern pattern = Pattern.compile("[Pp]assword=(.*?)(&|$)");
        Matcher matcher = pattern.matcher(uri);
        while (matcher.find()) {
            String sensitive = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);
            String mask = StringUtils.repeat("*", sensitive.length());
            uriBuffer.replace(start, end, mask);
        }
        return uriBuffer.toString();
    }

    /**
     * @return option from properties or from uri if it is not found in properties.
     * null if options was not found.
     */
    private String getOption(Properties properties, Map<String, List<String>> options, String optionName) {
        if (properties != null) {
            String option = (String) properties.get(optionName);
            if (option != null) {
                return option;
            }
        }
        return getLastValue(options, optionName);
    }

    CqlSession createCqlSession() throws IOException, GeneralSecurityException {
        CqlSessionBuilder builder = CqlSession.builder();
        int port = 9042;
        for ( String host : hosts ){
            int idx = host.indexOf(":");
            if ( idx > 0 ){
                port = Integer.parseInt( host.substring( idx +1).trim() );
                host = host.substring( 0, idx ).trim();
            }
            builder.addContactPoint( new InetSocketAddress( host, port ) );
            LOGGER.info("sslenabled: " + sslEnabled.toString());
            if (sslEnabled) {
                builder.withSslContext(getSslContext());
            }
            if (getConfigFile() != null) {
                File file = new File(this.getConfigFile());
                builder.withConfigLoader(DriverConfigLoader.fromFile(file));
            }
        }
        builder.withLocalDatacenter( dataCenter != null ? dataCenter : "datacenter1" );
        if ( userName != null && !userName.isEmpty() && password != null ) {
            builder.withAuthCredentials(userName, password);
            LOGGER.info("Authenticating as user '" + userName + "'");
        }
        return builder.build();
    }


    private String getLastValue(final Map<String, List<String>> optionsMap, final String key) {
        if (optionsMap == null) return null;
        List<String> valueList = optionsMap.get(key);
        if (valueList == null || valueList.size() == 0) return null;
        return valueList.get(valueList.size() - 1);
    }

    private Map<String, List<String>> parseOptions(String optionsPart) {
        Map<String, List<String>> optionsMap = new HashMap<>();

        for (String _part : optionsPart.split("[&;]")) {
            int idx = _part.indexOf("=");
            if (idx >= 0) {
                String key = _part.substring(0, idx).toLowerCase(Locale.ENGLISH);
                String value = _part.substring(idx + 1);
                List<String> valueList = optionsMap.get(key);
                if (valueList == null) {
                    valueList = new ArrayList<>(1);
                }
                valueList.add(value);
                optionsMap.put(key, valueList);
            }
        }

        return optionsMap;
    }

    public SSLContext getSslContext() throws GeneralSecurityException, IOException {
        String trustStore = this.trustStore;
        String trustStorePassword = this.trustStorePassword;
        String keyStore = this.keyStore;
        String keyStorePassword = this.keyStorePassword;

        if (keyStore == null) {
            keyStore = trustStore;
            keyStorePassword = trustStorePassword;
        }

        if (trustStore == null) {
            trustStore = keyStore;
            trustStorePassword  = keyStorePassword;
        }

        return getSslContext(trustStore, trustStorePassword, keyStore, keyStorePassword);
    }

    public SSLContext getSslContext(String trustStorePath, String trustStorePassword, String keyStorePath, String keyStorePassword)
            throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        char[] trustStorePasswordArray = trustStorePassword != null ? trustStorePassword.toCharArray() : null;
        try (InputStream in = new FileInputStream(trustStorePath)) {
            trustStore.load(in, trustStorePasswordArray);
        } catch (NullPointerException e) {
            trustStore.load(null, null);
        }
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(trustStore, trustStorePasswordArray);

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());

        try (InputStream in = new FileInputStream(keyStorePath)) {
            keyStore.load(in, keyStorePassword != null ? keyStorePassword.toCharArray() : null);
        } catch (NullPointerException e) {
            keyStore.load(null, null);
        }
        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
                keyManagerFactory.getKeyManagers(),
                trustManagerFactory.getTrustManagers(),
                new SecureRandom());
        return sslContext;
    }

    // ---------------------------------

    /**
     * Gets the username
     *
     * @return the username
     */
    public String getUsername() {
        return userName;
    }

    /**
     * Gets the password
     *
     * @return the password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Gets the ssl enabled property
     *
     * @return the ssl enabled property
     */
    public Boolean getSslEnabled() {
        return sslEnabled;
    }

    /**
     * Gets the list of hosts
     *
     * @return the host list
     */
    public List<String> getHosts() {
        return hosts;
    }

    /**
     * Gets the keyspace name
     *
     * @return the keyspace name
     */
    public String getKeyspace() {
        return keyspace;
    }


    /**
     * Gets the collection name
     *
     * @return the collection name
     */
    public String getCollection() {
        return collection;
    }

    /**
     * Get the unparsed URI.
     *
     * @return the URI
     */
    public String getURI() {
        return uri;
    }


    public String getTrustStore() {
        return trustStore;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    @Override
    public String toString() {
        return uri;
    }

    public String getConfigFile() {
        return configFile;
    }
}
