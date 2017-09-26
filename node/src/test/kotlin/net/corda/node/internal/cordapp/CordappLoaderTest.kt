package net.corda.node.internal.cordapp

import net.corda.core.flows.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.file.Paths

@InitiatingFlow
class DummyFlow : FlowLogic<Unit>() {
    override fun call() { }
}

@InitiatedBy(DummyFlow::class)
class LoaderTestFlow(unusedSession: FlowSession) : FlowLogic<Unit>() {
    override fun call() { }
}

@SchedulableFlow
class DummySchedulableFlow : FlowLogic<Unit>() {
    override fun call() { }
}

@StartableByRPC
class DummyRPCFlow : FlowLogic<Unit>() {
    override fun call() { }
}

class CordappLoaderTest {
    private companion object {
        val testScanPackages = listOf("net.corda.node.internal.cordapp")
    }

    @Test
    fun `test that classes that aren't in cordapps aren't loaded`() {
        // Basedir will not be a corda node directory so the dummy flow shouldn't be recognised as a part of a cordapp
        val loader = CordappLoader.createDefault(Paths.get("."))
        assertThat(loader.cordapps)
                .hasSize(1)
                .contains(CordappLoader.coreCordapp)
    }

    @Test
    fun `isolated JAR contains a CorDapp with a contract and plugin`() {
        val isolatedJAR = CordappLoaderTest::class.java.getResource("isolated.jar")!!
        val loader = CordappLoader.createDevMode(listOf(isolatedJAR))

        val actual = loader.cordapps.toTypedArray()
        assertThat(actual).hasSize(2)

        val actualCordapp = actual.single { it != CordappLoader.coreCordapp }
        assertThat(actualCordapp.contractClassNames).isEqualTo(listOf("net.corda.finance.contracts.isolated.AnotherDummyContract"))
        assertThat(actualCordapp.initiatedFlows).isEmpty()
        assertThat(actualCordapp.rpcFlows).isEmpty()
        assertThat(actualCordapp.schedulableFlows).isEmpty()
        assertThat(actualCordapp.services).isEmpty()
        assertThat(actualCordapp.plugins).hasSize(1)
        assertThat(actualCordapp.plugins.first().javaClass.name).isEqualTo("net.corda.finance.contracts.isolated.IsolatedPlugin")
        assertThat(actualCordapp.jarPath).isEqualTo(isolatedJAR)
    }

    @Test
    fun `flows are loaded by loader`() {
        val loader = CordappLoader.createWithTestPackages(testScanPackages)

        val actual = loader.cordapps.toTypedArray()
        // One core cordapp, one cordapp from this source tree, and two others due to identically named locations
        // in resources and the non-test part of node. This is okay due to this being test code. In production this
        // cannot happen.
        assertThat(actual).hasSize(4)

        val actualCordapp = actual.single { !it.initiatedFlows.isEmpty() }
        assertThat(actualCordapp.initiatedFlows).first().hasSameClassAs(DummyFlow::class.java)
        assertThat(actualCordapp.rpcFlows).first().hasSameClassAs(DummyRPCFlow::class.java)
        assertThat(actualCordapp.schedulableFlows).first().hasSameClassAs(DummySchedulableFlow::class.java)
    }
}
