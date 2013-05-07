package com.yahoo.ycsb.db;

import com.basho.riak.client.IRiakObject;
import com.basho.riak.client.builders.RiakObjectBuilder;
import com.basho.riak.client.raw.RawClient;
import com.basho.riak.client.raw.RiakResponse;
import com.basho.riak.client.raw.pbc.PBClientAdapter;
import com.basho.riak.client.raw.pbc.PBClientConfig;
import com.basho.riak.client.raw.pbc.PBClusterClientFactory;
import com.basho.riak.client.raw.pbc.PBClusterConfig;
import com.basho.riak.client.util.CharsetUtils;
import com.basho.riak.pbc.RiakClient;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.StringByteIterator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

public class RiakClient13 extends DB {
    public static final String RIAK_POOL_ENABLED = "riak_pool_enabled";
    public static final String RIAK_POOL_TOTAL_MAX_CONNECTION = "riak_pool_total_max_connection";
    public static final String RIAK_POOL_IDLE_CONNECTION_TTL_MILLIS = "riak_pool_idle_connection_ttl_millis";
    public static final String RIAK_POOL_INITIAL_POOL_SIZE = "riak_pool_initial_pool_size";
    public static final String RIAK_POOL_REQUEST_TIMEOUT_MILLIS = "riak_pool_request_timeout_millis";
    public static final String RIAK_POOL_CONNECTION_TIMEOUT_MILLIS = "riak_pool_connection_timeout_millis";
    private RawClient rawClient;
    public static final int OK = 0;
    public static final int ERROR = -1;

    public static final String RIAK_CLUSTER_HOSTS = "riak_cluster_hosts";
    public static final String RIAK_CLUSTER_HOST_DEFAULT = "127.0.0.1:10017";

    public static final int RIAK_POOL_TOTAL_MAX_CONNECTIONS_DEFAULT = 50;
    public static final int RIAK_POOL_IDLE_CONNETION_TTL_MILLIS_DEFAULT = 1000;
    public static final int RIAK_POOL_INITIAL_POOL_SIZE_DEFAULT = 5;
    public static final int RIAK_POOL_REQUEST_TIMEOUT_MILLIS_DEFAULT = 1000;
    public static final int RIAK_POOL_CONNECTION_TIMEOUT_MILLIS_DEFAULT = 1000;

    private static final Charset CHARSET_UTF8 = Charset.forName("UTF-8");
    private static final String CONTENT_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";

    ObjectMapper om = new ObjectMapper();

    private static int connectionNumber = 0;

    private int getIntProperty(Properties props, String propname, int defaultValue) {
        String stringProp = props.getProperty(propname, "" + defaultValue);
        return Integer.parseInt(stringProp);
    }

    public void init() throws DBException {
        try {
            Properties props = getProperties();
            String cluster_hosts = props.getProperty(RIAK_CLUSTER_HOSTS, RIAK_CLUSTER_HOST_DEFAULT);
            String[] servers = cluster_hosts.split(",");
            boolean useConnectionPool = true;
            if(props.containsKey(RIAK_POOL_ENABLED)) {
                String usePool = props.getProperty(RIAK_POOL_ENABLED);
                useConnectionPool = Boolean.parseBoolean(usePool);
            }
            if(useConnectionPool) {
                setupConnectionPool(props, servers);
            }  else {
                setupConnections(props, servers);
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new DBException("Error connecting to Riak: " + e.getMessage());
        }
    }

    private void setupConnections(Properties props, String[] servers) throws IOException {
        String server = servers[connectionNumber++ % servers.length];
        String[] ipAndPort = server.split(":");
        String ip = ipAndPort[0].trim();
        int port = Integer.parseInt(ipAndPort[1].trim());
        //System.out.println("Riak connection to " + ip + ":" + port);
        RiakClient pbcClient = new RiakClient(ip, port);
        rawClient = new PBClientAdapter(pbcClient);
    }

    private void setupConnectionPool(Properties props, String[] servers) throws IOException {

        int poolMaxConnections =
                getIntProperty(props,
                        RIAK_POOL_TOTAL_MAX_CONNECTION,
                        RIAK_POOL_TOTAL_MAX_CONNECTIONS_DEFAULT);
        int poolIdleConnectionTtlMillis =
                getIntProperty(props,
                        RIAK_POOL_IDLE_CONNECTION_TTL_MILLIS,
                        RIAK_POOL_IDLE_CONNETION_TTL_MILLIS_DEFAULT);
        int poolInitialPoolSize =
                getIntProperty(props,
                        RIAK_POOL_INITIAL_POOL_SIZE,
                        RIAK_POOL_INITIAL_POOL_SIZE_DEFAULT);
        int poolRequestTimeoutMillis =
                getIntProperty(props,
                        RIAK_POOL_REQUEST_TIMEOUT_MILLIS,
                        RIAK_POOL_REQUEST_TIMEOUT_MILLIS_DEFAULT);
        int poolConnectionTimeoutMillis =
                getIntProperty(props,
                        RIAK_POOL_CONNECTION_TIMEOUT_MILLIS,
                        RIAK_POOL_CONNECTION_TIMEOUT_MILLIS_DEFAULT);

        PBClusterConfig clusterConf = new PBClusterConfig(poolMaxConnections);
        for(String server:servers) {
            String[] ipAndPort = server.split(":");
            String ip = ipAndPort[0].trim();
            int port = Integer.parseInt(ipAndPort[1].trim());
            //System.out.println("Riak connection to " + ip + ":" + port);
            PBClientConfig node = PBClientConfig.Builder
                    .from(PBClientConfig.defaults())
                    .withHost(ip)
                    .withPort(port)
                    .withIdleConnectionTTLMillis(poolIdleConnectionTtlMillis)
                    .withInitialPoolSize(poolInitialPoolSize)
                    .withRequestTimeoutMillis(poolRequestTimeoutMillis)
                    .withConnectionTimeoutMillis(poolConnectionTimeoutMillis)
                    .build();
            clusterConf.addClient(node);
        }
        rawClient = PBClusterClientFactory.getInstance().newClient(clusterConf);
    }

    public void cleanup() throws DBException {
        rawClient.shutdown();
    }

    public int read(String bucket, String key, Set<String> fields,
                    HashMap<String, ByteIterator> result) {
        try {
            RiakResponse response = rawClient.fetch(bucket, key);
            if(response.hasValue()) {
                IRiakObject obj = response.getRiakObjects()[0];
                riakObjToJson(obj, fields, result);
            }
        } catch(Exception e) {
            e.printStackTrace();
            return ERROR;
        }
        return OK;
    }

    public int scan(String bucket, String startkey, int recordcount,
                    Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
        return OK;
    }

    public int update(String bucket, String key,
                      HashMap<String, ByteIterator> values) {
        try {
            RiakResponse response = rawClient.fetch(bucket, key);
            if(response.hasValue()) {
                IRiakObject obj = response.getRiakObjects()[0];
                byte[] data = updateJson(obj, values);
                RiakObjectBuilder builder =
                        RiakObjectBuilder.newBuilder(bucket, key)
                                .withContentType(CONTENT_TYPE_JSON_UTF8)
                                .withValue(data)
                                .withVClock(response.getVclock());
                rawClient.store(builder.build());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ERROR;
        }
        return OK;
    }

    public int insert(String bucket, String key,
                      HashMap<String, ByteIterator> values) {
        RiakObjectBuilder builder =
                RiakObjectBuilder.newBuilder(bucket, key)
                                 .withContentType(CONTENT_TYPE_JSON_UTF8);
        try {
            byte[] rawValue = jsonToBytes(values);
            rawClient.store(builder.withValue(rawValue).build());
        } catch (Exception e) {
            e.printStackTrace();
            return ERROR;
        }

        return OK;
    }

    public int delete(String bucket, String key) {
        try {
            rawClient.delete(bucket, key);
        } catch (IOException e) {
            e.printStackTrace();
            return ERROR;
        }
        return OK;
    }

    private byte[] updateJson(IRiakObject object, Map<String, ByteIterator> values) throws IOException {
        String contentType = object.getContentType();
        Charset charSet = CharsetUtils.getCharset(contentType);
        byte[] data = object.getValue();
        String dataInCharset = CharsetUtils.asString(data, charSet);
        JsonNode jsonNode = om.readTree(dataInCharset);
        for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
            ((ObjectNode) jsonNode).put(entry.getKey(), entry.getValue().toString());
        }
        return jsonNode.toString().getBytes(CHARSET_UTF8);
    }

    private byte[] jsonToBytes(Map<String, ByteIterator> values) {
        ObjectNode objNode = om.createObjectNode();
        for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
            objNode.put(entry.getKey(), entry.getValue().toString());
        }
        return objNode.toString().getBytes(CHARSET_UTF8);
    }

    public void riakObjToJson(IRiakObject object, Set<String> fields, Map<String, ByteIterator> result)
            throws IOException {
        String contentType = object.getContentType();
        Charset charSet = CharsetUtils.getCharset(contentType);
        byte[] data = object.getValue();
        String dataInCharset = CharsetUtils.asString(data, charSet);
        JsonNode jsonNode = om.readTree(dataInCharset);
        if(fields != null) {
            // return a subset of all available fields in the json node
            for(String field: fields) {
                JsonNode f = jsonNode.get(field);
                result.put(field, new StringByteIterator(f.toString()));
            }
        } else {
          // no fields specified, just return them all
          Iterator<Map.Entry<String, JsonNode>> jsonFields = jsonNode.getFields();
          while(jsonFields.hasNext()) {
              Map.Entry<String, JsonNode> field = jsonFields.next();
                result.put(field.getKey(), new StringByteIterator(field.getValue().toString()));
          }
        }
    }

    public static void main(String[] args)
    {
        RiakClient13 cli = new RiakClient13();

        Properties props = new Properties();
        props.setProperty(RIAK_CLUSTER_HOSTS, "localhost:10017, localhost:10027, localhost:10037");

        cli.setProperties(props);

        try {
            cli.init();
        } catch(Exception e) {
            e.printStackTrace();
        }


        String bucket = "people";
        String key = "person1";

        {
            HashMap<String, String> values = new HashMap<String, String>();
            values.put("first_name", "Dave");
            values.put("last_name", "Parfitt");
            values.put("city", "Buffalo, NY");
            cli.insert(bucket, key, StringByteIterator.getByteIteratorMap(values));
            System.out.println("Added person");
        }

        {
            Set<String> fields = new HashSet<String>();
            fields.add("first_name");
            fields.add("last_name");
            HashMap<String, ByteIterator> results = new HashMap<String, ByteIterator>();
            cli.read(bucket, key,fields, results);
            System.out.println(results.toString());
            System.out.println("Read person");
        }

        {
            HashMap<String, String> updateValues = new HashMap<String, String>();
            updateValues.put("twitter", "@metadave");
            cli.update("people", "person1", StringByteIterator.getByteIteratorMap(updateValues));
            System.out.println("Updated person");
        }

        {
            HashMap<String, ByteIterator> finalResults = new HashMap<String, ByteIterator>();
            cli.read(bucket, key, null, finalResults);
            System.out.println(finalResults.toString());
            System.out.println("Read person");
        }
    }
}
