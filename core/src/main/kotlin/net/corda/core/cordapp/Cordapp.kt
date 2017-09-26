package net.corda.core.cordapp

import net.corda.core.flows.FlowLogic
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.SerializeAsToken
import java.net.URL

/**
 * Represents a cordapp by registering the JAR that contains it and all important classes for Corda.
 * Instances of this class are generated automatically at startup of a node and can get retrieved from
 * [CordappProvider.getAppContext] from the [CordappContext] it returns.
 *
 * This will only need to be constructed manually for certain kinds of tests.
 *
 * @property contractClassNames List of contracts
 * @property initiatedFlows List of initiatable flow classes
 * @property rpcFlows List of RPC initiable flows classes
 * @property schedulableFlows List of flows initiated by scheduler
 * @property servies List of RPC services
 * @property plugins List of Corda plugin registries
 * @property customSchemas List of custom schemas
 * @property jarPath The path to the JAR for this CorDapp
 */
interface Cordapp {
    val contractClassNames: List<String>
    val initiatedFlows: List<Class<out FlowLogic<*>>>
    val rpcFlows: List<Class<out FlowLogic<*>>>
    val schedulableFlows: List<Class<out FlowLogic<*>>>
    val services: List<Class<out SerializeAsToken>>
    val plugins: List<CordaPluginRegistry>
    val customSchemas: Set<MappedSchema>
    val jarPath: URL
    val cordappClasses: List<String>
}