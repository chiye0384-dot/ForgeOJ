/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.testinfra;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Only fixed-Linux test JVMs opt into the verified sibling-container route. */
public final class DirectMySQLContainer extends MySQLContainer {
    private String cachedContainerId;
    private String cachedAddress;
    public DirectMySQLContainer(DockerImageName image) { super(image); }
    @Override public synchronized String getJdbcUrl() {
        String original=super.getJdbcUrl();
        if(!"1".equals(System.getenv("FORGEOJ_TEST_DIRECT_DB"))) return original;
        String id=getContainerId();
        if(id==null) throw new IllegalStateException("Direct test database is not started");
        if(!id.equals(cachedContainerId)) {
            var network=getDockerClient().inspectContainerCmd(id).exec().getNetworkSettings().getNetworks().get("bridge");
            String address=network==null ? null : network.getIpAddress();
            if(address==null || !address.matches("[0-9]+(?:\\.[0-9]+){3}")) throw new IllegalStateException("Direct test database route unavailable");
            cachedAddress=address;cachedContainerId=id;
        }
        return "jdbc:mysql://"+cachedAddress+":3306"+original.substring(original.indexOf('/',"jdbc:mysql://".length()));
    }
}