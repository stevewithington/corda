package net.corda.core.internal.cordapp

import net.corda.core.cordapp.Cordapp
import net.corda.core.flows.FlowLogic
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.SerializeAsToken
import java.net.URL

data class CordappImpl(
        override val contractClassNames: List<String>,
        override val initiatedFlows: List<Class<out FlowLogic<*>>>,
        override val rpcFlows: List<Class<out FlowLogic<*>>>,
        override val schedulableFlows: List<Class<out FlowLogic<*>>>,
        override val services: List<Class<out SerializeAsToken>>,
        override val plugins: List<CordaPluginRegistry>,
        override val customSchemas: Set<MappedSchema>,
        override val jarPath: URL) : Cordapp {
    /**
     * An exhaustive list of all classes relevant to the node within this CorDapp
     *
     * TODO: Also add [SchedulableFlow] as a Cordapp class
     */
    override val cordappClasses = ((rpcFlows + initiatedFlows + services + plugins.map { javaClass }).map { it.name } + contractClassNames)
}
