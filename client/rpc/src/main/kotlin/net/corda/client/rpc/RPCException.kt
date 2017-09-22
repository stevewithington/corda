package net.corda.client.rpc

import net.corda.core.CordaRuntimeException

/**
 * Thrown to indicate a fatal error in the RPC system itself, as opposed to an error generated by the invoked
 * method.
 */
open class RPCException(message: String?, cause: Throwable?) : CordaRuntimeException(message, cause) {
    constructor(msg: String) : this(msg, null)
}
