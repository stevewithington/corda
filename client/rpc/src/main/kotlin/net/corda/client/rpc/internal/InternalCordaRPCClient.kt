package net.corda.client.rpc.internal

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCClientConfiguration
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.config.SSLConfiguration

fun internalCordaRPCClient(hostAndPort: NetworkHostAndPort,
                           sslConfiguration: SSLConfiguration? = null,
                           configuration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.default,
                           initialiseSerialization: Boolean = true): CordaRPCClient {
    return CordaRPCClient.bridgeCordaRPCClient(hostAndPort, sslConfiguration, configuration, initialiseSerialization)
}