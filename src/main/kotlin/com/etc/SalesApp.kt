package com.etc

import com.etc.contract.SalesContract
import com.etc.contract.states.SalesState
import com.etc.services.SalesContractApi
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.serialization.SerializationWhitelist
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function


// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val SALE_CONTRACT_ID = "com.etc.contract.SalesContract"



class SalesContractWebPlugin : WebServerPluginRegistry {
    // A list of classes that expose web JAX-RS REST APIs.
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::SalesContractApi))
    //A list of directories in the resources directory that will be served by Jetty under /web.
    // This template's web frontend is accessible at /web/template.
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the templateWeb directory in resources to /web/template
            "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )
}

// Serialization whitelist.
class TemplateSerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(SalesState::class.java,
            SalesState.Status::class.java,
            SalesContract.SaleCommands::class.java)
}