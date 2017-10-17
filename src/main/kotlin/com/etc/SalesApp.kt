package com.etc

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.reflect.jvm.jvmName

// *****************
// * API Endpoints *
// *****************
@Path("sale")
class TemplateApi(val rpcOps: CordaRPCOps) {
    // Accessible at /api/template/templateGetEndpoint.
    @GET
    @Path("saleGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.ok("Sale contract GET endpoint.").build()
    }
}

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val SALE_CONTRACT_ID = "com.etc.SalesContract"

class SalesContract : Contract {


    interface SaleCommands : CommandData {
        class Create : TypeOnlyCommandData(), SaleCommands
        class Update : TypeOnlyCommandData(), SaleCommands
    }

    override fun verify(tx: LedgerTransaction) {

        val groups = tx.groupStates() { it: SalesState -> it.all() }
        val command = tx.commands.requireSingleCommand<SaleCommands>()


        for ((inputs, outputs, key) in groups) {
            when (command.value) {
                is SaleCommands.Create -> {
                    val output = outputs.single()
                    requireThat {
                        "Buyer cannot be a seller for a transaction" using (output.sellerIsNotBuyer())
                        "Contract can only be created with status proposed" using (output.status == SalesState.Status.PROPOSED)
                    }
                }

                is SaleCommands.Update -> {
                    val input = inputs.single()
                    requireThat {
                        "Cannot update rejected Contract" using (input.status != SalesState.Status.REJECTED)
                    }
                }

            }
        }
    }
}


// *********
// * State *
// *********
data class SalesState(
        var buyer: Party,
        var seller: Party,
        var data_hash: String? = null,
        var signatureBuyer: String? = null,
        var signatureSeller: String? = null,
        var status: Status? = Status.PROPOSED
) : ContractState {

    enum class Status {
        PROPOSED,
        ACCEPTED,
        REJECTED
    }


    override val participants: List<Party> get() = listOf(buyer, seller)

    fun all() = copy()
    fun sellerIsNotBuyer(): Boolean {
        return !buyer.equals(seller)
    }

    fun accept(hash_signature: String, sender: Party): ContractState {
        if (sender.equals(buyer)) {
            return copy(status = Status.ACCEPTED, signatureBuyer = hash_signature)
        }
        return copy()
    }

    fun reject(sender: Party): ContractState {
        if (sender.equals(buyer) && status != Status.ACCEPTED) {
            return copy(status = Status.REJECTED)
        }
        return copy()
    }


}

// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class SaleFlow(val buyer: Party,
               val seller: Party,
               val hash: String) : FlowLogic<Unit>() {

    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call() {
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val txBuilder = TransactionBuilder(notary = notary)

        val outputState = SalesState(buyer, seller, hash)
        val outputContract = SalesContract::class.jvmName

        val outputContractAndState = StateAndContract(outputState, outputContract)

        val contractHash = serviceHub.cordappProvider.getContractAttachmentID(SalesContract::class.jvmName)


        val cmd = Command(SalesContract.SaleCommands.Create(), seller.owningKey)


        // We add the items to the builder.
        if (contractHash != null) {
            txBuilder.withItems(outputContractAndState, cmd, contractHash)
        }

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Finalising the transaction.
        subFlow(FinalityFlow(signedTx))
    }
}

@InitiatedBy(SaleFlow::class)
class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        return Unit
    }
}

//class TemplateWebPlugin : WebServerPluginRegistry {
//    // A list of classes that expose web JAX-RS REST APIs.
//    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TemplateApi))
//    //A list of directories in the resources directory that will be served by Jetty under /web.
//    // This template's web frontend is accessible at /web/template.
//    override val staticServeDirs: Map<String, String> = mapOf(
//            // This will serve the templateWeb directory in resources to /web/template
//            "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
//    )
//}

// Serialization whitelist.
class TemplateSerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(com.etc.SalesState::class.java,
            com.etc.SalesState.Status::class.java,
            com.etc.SalesContract.SaleCommands::class.java)
}

// This class is not annotated with @CordaSerializable, so it must be added to the serialization whitelist, above, if
// we want to send it to other nodes within a flow.
//data class TemplateData(val payload: String)