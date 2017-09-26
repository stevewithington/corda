package net.corda.core.flows

import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.node.internal.StartedNode
import net.corda.testing.*
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockServices
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FinalityFlowTests {
    lateinit var mockNet: MockNetwork
    lateinit var nodeA: StartedNode<MockNetwork.MockNode>
    lateinit var nodeB: StartedNode<MockNetwork.MockNode>
    lateinit var notary: Party
    val services = MockServices()

    @Before
    fun setup() {
        setCordappPackages("net.corda.finance.contracts.asset")
        mockNet = MockNetwork()
        val notaryNode = mockNet.createNotaryNode(null, DUMMY_NOTARY.name)
        nodeA = mockNet.createPartyNode(notaryNode.network.myAddress, ALICE.name)
        nodeB = mockNet.createPartyNode(notaryNode.network.myAddress, BOB.name)
        mockNet.runNetwork()
        nodeA.internals.ensureRegistered()
        notary = nodeA.services.getDefaultNotary()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
        unsetCordappPackages()
    }

    @Test
    fun `finalise a simple transaction`() {
        val amount = 1000.POUNDS.issuedBy(nodeA.info.chooseIdentity().ref(0))
        val builder = TransactionBuilder(notary)
        Cash().generateIssue(builder, amount, nodeB.info.chooseIdentity(), notary)
        val stx = nodeA.services.signInitialTransaction(builder)
        val flow = nodeA.services.startFlow(FinalityFlow(stx))
        mockNet.runNetwork()
        val notarisedTx = flow.resultFuture.getOrThrow()
        notarisedTx.verifyRequiredSignatures()
        val transactionSeenByB = nodeB.services.database.transaction {
            nodeB.services.validatedTransactions.getTransaction(notarisedTx.id)
        }
        assertEquals(notarisedTx, transactionSeenByB)
    }

    @Test
    fun `reject a transaction with unknown parties`() {
        val amount = 1000.POUNDS.issuedBy(nodeA.info.chooseIdentity().ref(0))
        val fakeIdentity = ALICE // Alice isn't part of this network, so node A won't recognise them
        val builder = TransactionBuilder(notary)
        Cash().generateIssue(builder, amount, fakeIdentity, notary)
        val stx = nodeA.services.signInitialTransaction(builder)
        val flow = nodeA.services.startFlow(FinalityFlow(stx))
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> {
            flow.resultFuture.getOrThrow()
        }
    }
}