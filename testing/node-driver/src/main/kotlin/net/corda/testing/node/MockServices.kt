package net.corda.testing.node

import net.corda.core.crypto.*
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.messaging.DataFeed
import net.corda.core.node.NodeInfo
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.*
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.NonEmptySet
import net.corda.node.VersionInfo
import net.corda.node.services.api.StateMachineRecordedTransactionMappingStorage
import net.corda.node.services.api.WritableTransactionStorage
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.node.services.keys.freshCertificate
import net.corda.node.services.keys.getSigner
import net.corda.node.services.persistence.HibernateConfiguration
import net.corda.node.services.persistence.InMemoryStateMachineRecordedTransactionMappingStorage
import net.corda.node.services.schema.HibernateObserver
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.transactions.InMemoryTransactionVerifierService
import net.corda.node.services.vault.HibernateVaultQueryImpl
import net.corda.node.services.vault.NodeVaultService
import net.corda.node.utilities.CordaPersistence
import net.corda.node.utilities.configureDatabase
import net.corda.testing.*
import org.bouncycastle.operator.ContentSigner
import rx.Observable
import rx.subjects.PublishSubject
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.sql.Connection
import java.time.Clock
import java.util.*

// TODO: We need a single, rationalised unit testing environment that is usable for everything. Fix this!
// That means it probably shouldn't be in the 'core' module, which lacks enough code to create a realistic test env.

/**
 * A singleton utility that only provides a mock identity, key and storage service. However, this is sufficient for
 * building chains of transactions and verifying them. It isn't sufficient for testing flows however.
 */
open class MockServices(vararg val keys: KeyPair) : ServiceHub {

    companion object {

        @JvmStatic
        val MOCK_VERSION_INFO = VersionInfo(1, "Mock release", "Mock revision", "Mock Vendor")

        /**
         * Make properties appropriate for creating a DataSource for unit tests.
         *
         * @param nodeName Reflects the "instance" of the in-memory database.  Defaults to a random string.
         */
        // TODO: Can we use an X509 principal generator here?
        @JvmStatic
        fun makeTestDataSourceProperties(nodeName: String = SecureHash.randomSHA256().toString()): Properties {
            val props = Properties()
            props.setProperty("dataSourceClassName", "org.h2.jdbcx.JdbcDataSource")
            props.setProperty("dataSource.url", "jdbc:h2:mem:${nodeName}_persistence;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE")
            props.setProperty("dataSource.user", "sa")
            props.setProperty("dataSource.password", "")
            return props
        }

        /**
         * Make properties appropriate for creating a Database for unit tests.
         *
         * @param key (optional) key of a database property to be set.
         * @param value (optional) value of a database property to be set.
         */
        @JvmStatic
        fun makeTestDatabaseProperties(key: String? = null, value: String? = null): Properties {
            val props = Properties()
            props.setProperty("transactionIsolationLevel", "repeatableRead") //for other possible values see net.corda.node.utilities.CordaPeristence.parserTransactionIsolationLevel(String)
            if (key != null) { props.setProperty(key, value) }
            return props
        }

        /**
         * Creates an instance of [InMemoryIdentityService] with [MOCK_IDENTITIES].
         */
        @JvmStatic
        fun makeTestIdentityService() = InMemoryIdentityService(MOCK_IDENTITIES, trustRoot = DEV_TRUST_ROOT)

        /**
         * Makes database and mock services appropriate for unit tests.
         *
         * @param customSchemas a set of schemas being used by [NodeSchemaService]
         * @param keys a lis of [KeyPair] instances to be used by [MockServices]. Defualts to [MEGA_CORP_KEY]
         * @param createIdentityService a lambda function returning an instance of [IdentityService]. Defauts to [InMemoryIdentityService].
         *
         * @return a pair where the first element is the instance of [CordaPersistence] and the second is [MockServices].
         */
        @JvmStatic
        fun makeTestDatabaseAndMockServices(customSchemas: Set<MappedSchema> = emptySet(),
                                            keys: List<KeyPair> = listOf(MEGA_CORP_KEY),
                                            createIdentityService: () -> IdentityService = { makeTestIdentityService() }): Pair<CordaPersistence, MockServices> {
            val dataSourceProps = makeTestDataSourceProperties()
            val databaseProperties = makeTestDatabaseProperties()
            val createSchemaService = { NodeSchemaService(customSchemas) }
            val identityServiceRef: IdentityService by lazy { createIdentityService() }
            val database = configureDatabase(dataSourceProps, databaseProperties, createSchemaService, { identityServiceRef })
            val mockService = database.transaction {
                object : MockServices(*(keys.toTypedArray())) {
                    override val identityService: IdentityService = database.transaction { identityServiceRef }
                    override val vaultService: VaultService = makeVaultService(database.hibernateConfig)

                    override fun recordTransactions(notifyVault: Boolean, txs: Iterable<SignedTransaction>) {
                        for (stx in txs) {
                            validatedTransactions.addTransaction(stx)
                        }
                        // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                        (vaultService as NodeVaultService).notifyAll(txs.map { it.tx })
                    }

                    override val vaultQueryService: VaultQueryService = HibernateVaultQueryImpl(database.hibernateConfig, vaultService)

                    override fun jdbcSession(): Connection = database.createSession()
                }
            }
            return Pair(database, mockService)
        }
    }

    constructor() : this(generateKeyPair())

    val key: KeyPair get() = keys.first()

    override fun recordTransactions(notifyVault: Boolean, txs: Iterable<SignedTransaction>) {
        txs.forEach {
            stateMachineRecordedTransactionMapping.addMapping(StateMachineRunId.createRandom(), it.id)
        }
        for (stx in txs) {
            validatedTransactions.addTransaction(stx)
        }
    }

    override val attachments: AttachmentStorage = MockAttachmentStorage()
    override val validatedTransactions: WritableTransactionStorage = MockTransactionStorage()
    val stateMachineRecordedTransactionMapping: StateMachineRecordedTransactionMappingStorage = MockStateMachineRecordedTransactionMappingStorage()
    override val identityService: IdentityService = InMemoryIdentityService(MOCK_IDENTITIES, trustRoot = DEV_TRUST_ROOT)
    override val keyManagementService: KeyManagementService by lazy { MockKeyManagementService(identityService, *keys) }

    override val vaultService: VaultService get() = throw UnsupportedOperationException()
    override val contractUpgradeService: ContractUpgradeService get() = throw UnsupportedOperationException()
    override val vaultQueryService: VaultQueryService get() = throw UnsupportedOperationException()
    override val networkMapCache: NetworkMapCache get() = throw UnsupportedOperationException()
    override val clock: Clock get() = Clock.systemUTC()
    override val myInfo: NodeInfo get() {
        val identity = getTestPartyAndCertificate(MEGA_CORP.name, key.public)
        return NodeInfo(emptyList(), listOf(identity), 1,  serial = 1L)
    }
    override val notaryIdentityKey: PublicKey get()  = throw UnsupportedOperationException()
    override val transactionVerifierService: TransactionVerifierService get() = InMemoryTransactionVerifierService(2)

    lateinit var hibernatePersister: HibernateObserver

    fun makeVaultService(hibernateConfig: HibernateConfiguration = HibernateConfiguration(NodeSchemaService(), makeTestDatabaseProperties(), { identityService })): VaultService {
        val vaultService = NodeVaultService(this)
        hibernatePersister = HibernateObserver(vaultService.rawUpdates, hibernateConfig)
        return vaultService
    }

    override fun <T : SerializeAsToken> cordaService(type: Class<T>): T = throw IllegalArgumentException("${type.name} not found")

    override fun jdbcSession(): Connection = throw UnsupportedOperationException()
}

class MockKeyManagementService(val identityService: IdentityService,
                               vararg initialKeys: KeyPair) : SingletonSerializeAsToken(), KeyManagementService {
    private val keyStore: MutableMap<PublicKey, PrivateKey> = initialKeys.associateByTo(HashMap(), { it.public }, { it.private })

    override val keys: Set<PublicKey> get() = keyStore.keys

    val nextKeys = LinkedList<KeyPair>()

    override fun freshKey(): PublicKey {
        val k = nextKeys.poll() ?: generateKeyPair()
        keyStore[k.public] = k.private
        return k.public
    }

    override fun filterMyKeys(candidateKeys: Iterable<PublicKey>): Iterable<PublicKey> = candidateKeys.filter { it in this.keys }

    override fun freshKeyAndCert(identity: PartyAndCertificate, revocationEnabled: Boolean): PartyAndCertificate {
        return freshCertificate(identityService, freshKey(), identity, getSigner(identity.owningKey), revocationEnabled)
    }

    private fun getSigner(publicKey: PublicKey): ContentSigner = getSigner(getSigningKeyPair(publicKey))

    private fun getSigningKeyPair(publicKey: PublicKey): KeyPair {
        val pk = publicKey.keys.first { keyStore.containsKey(it) }
        return KeyPair(pk, keyStore[pk]!!)
    }

    override fun sign(bytes: ByteArray, publicKey: PublicKey): DigitalSignature.WithKey {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(bytes)
    }

    override fun sign(signableData: SignableData, publicKey: PublicKey): TransactionSignature {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(signableData)
    }
}

class MockStateMachineRecordedTransactionMappingStorage(
        val storage: StateMachineRecordedTransactionMappingStorage = InMemoryStateMachineRecordedTransactionMappingStorage()
) : StateMachineRecordedTransactionMappingStorage by storage

open class MockTransactionStorage : WritableTransactionStorage, SingletonSerializeAsToken() {
    override fun track(): DataFeed<List<SignedTransaction>, SignedTransaction> {
        return DataFeed(txns.values.toList(), _updatesPublisher)
    }

    private val txns = HashMap<SecureHash, SignedTransaction>()

    private val _updatesPublisher = PublishSubject.create<SignedTransaction>()

    override val updates: Observable<SignedTransaction>
        get() = _updatesPublisher

    private fun notify(transaction: SignedTransaction) = _updatesPublisher.onNext(transaction)

    override fun addTransaction(transaction: SignedTransaction): Boolean {
        val recorded = txns.putIfAbsent(transaction.id, transaction) == null
        if (recorded) {
            notify(transaction)
        }
        return recorded
    }

    override fun getTransaction(id: SecureHash): SignedTransaction? = txns[id]
}
