package com.etc

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.getOrThrow
import net.corda.webserver.services.WebServerPluginRegistry
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.reflect.jvm.jvmName
import java.util.function.Function
import javax.ws.rs.*

// *****************
// * API Endpoints *
// *****************
@Path("sale")
class SalesContractApi(val rpc: CordaRPCOps) {


    class Contract {
        var buyer: String? = null
        var seller: String? = null
        var contractData: String? = null
        var notary: String? = null
    }

    // Accessible at /api/sale/saleGetEndpoint.
    @GET
    @Path("saleGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun saleGetEndpoint(): Response {
        return Response.ok("Sale contract GET endpoint.").build()
    }


    // Accessible at /api/sale/createSaleContract.
    @POST
    @Path("createSaleContract")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun createSaleContractBuyer(draftSaleContract: Contract): Response {


        val buyerParty = rpc.partiesFromName(draftSaleContract.buyer.toString(), true).firstOrNull()
                ?: return Response.status(Response.Status.FORBIDDEN).entity("Unable to locate ${draftSaleContract.buyer} in identity service").build()


        val sellerParty = rpc.partiesFromName(draftSaleContract.seller.toString(), true).firstOrNull()
                ?: return Response.status(Response.Status.FORBIDDEN).entity("Unable to locate ${draftSaleContract.seller} in identity service").build()


        return try {
            val id = rpc.startFlow(::SaleCreateFlow, buyerParty, sellerParty, draftSaleContract.contractData.toString())
                    .returnValue.getOrThrow()

            Response.status(Response.Status.CREATED).entity(id).build()
        } catch (e: Exception) {
            Response.status(Response.Status.FORBIDDEN).build()
        }

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
//
//@StartableByRPC
//class SaleAcceptContractFlow() {
//
//}

@InitiatedBy(SaleCreateFlow::class)
class SaleCreateResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Sale transaction." using (output is SalesState)
            }
        }

        subFlow(signTransactionFlow)
    }
}


@InitiatingFlow
@StartableByRPC
/*Seller propose Global terme and condition to Buyer*/
class SaleCreateFlow(val buyer: Party,
                     val seller: Party,
                     val hash: String) : FlowLogic<Unit>() {

    companion object {
        object WAITING_SALE_CONTRACT_PROPOSITION : ProgressTracker.Step("Waiting for proposition")

        object PROPOSAL_FROM_SELLER : ProgressTracker.Step("Seller propose sale contract")

        object VERIFYING_AND_APPROVE_BY_BUYER : ProgressTracker.Step("Buyer Approved sale contract") {
            override fun childProgressTracker() = SignTransactionFlow.tracker()
        }

        object VERIFYING_AND_REJECT_BY_BUYER : ProgressTracker.Step("Buyer reject sale contract") {
            override fun childProgressTracker() = SignTransactionFlow.tracker()
        }


        fun tracker() = ProgressTracker(WAITING_SALE_CONTRACT_PROPOSITION, PROPOSAL_FROM_SELLER, VERIFYING_AND_APPROVE_BY_BUYER, VERIFYING_AND_REJECT_BY_BUYER)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call() {

        progressTracker.currentStep = WAITING_SALE_CONTRACT_PROPOSITION

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

        //buyer sign
        val otherpartySession = initiateFlow(buyer)
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherpartySession), CollectSignaturesFlow.tracker()))


        // Finalising the transaction.
        subFlow(FinalityFlow(fullySignedTx))


    }
}


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
    override val whitelist: List<Class<*>> = listOf(com.etc.SalesState::class.java,
            com.etc.SalesState.Status::class.java,
            com.etc.SalesContract.SaleCommands::class.java)
}

// This class is not annotated with @CordaSerializable, so it must be added to the serialization whitelist, above, if
// we want to send it to other nodes within a flow.
//data class TemplateData(val payload: String)