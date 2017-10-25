package com.etc

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.noneOrSingle
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
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


    //acceptSaleContract
    @POST
    @Path("acceptSaleContract")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun acceptSaleContractBuyer(contractId: String): Response {


        val results = rpc.vaultQueryBy<SalesState>()

        return try {

            val txState = results.states.firstOrNull { currentState ->
                currentState.ref.txhash.toString().equals(contractId)
            }

            if (txState != null) {
                val id = rpc.startFlow(::AcceptContractFlow, txState)
                        .returnValue.getOrThrow()
                Response.status(Response.Status.CREATED).entity(id).build()
            } else {
                return Response.status(Response.Status.NOT_FOUND).build()
            }
        } catch (e: Exception) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }

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
        class Accept : TypeOnlyCommandData(), SaleCommands
        class Reject : TypeOnlyCommandData(), SaleCommands
    }

    override fun verify(tx: LedgerTransaction) {

        val groups = tx.groupStates() { it: SalesState -> it.all() }
        val command = tx.commands.requireSingleCommand<SaleCommands>()


        for ((inputs, outputs, key) in groups) {
            when (command.value) {
                is SaleCommands.Create -> {
                    val output = outputs.noneOrSingle()
                    if (output != null) {
                        requireThat {
                            "Buyer cannot be a seller for a transaction" using (output.sellerIsNotBuyer())
                            "Contract can only be created with status proposed" using (output.status == SalesState.Status.PROPOSED)
                        }
                    }

                }

                is SaleCommands.Update -> {
                    val input = inputs.noneOrSingle()
                    if (input != null) {
                        requireThat {
                            "Cannot update rejected Contract" using (input.status != SalesState.Status.REJECTED)
                        }
                    }
                }

                is SaleCommands.Accept -> {
                    val output = outputs.noneOrSingle()
                    if (output != null) {
                        requireThat {
                            "Only Buyer Can accept Contract" using (command.signers.first().equals(output.buyer.owningKey))
                        }
                    }
                }
                is SaleCommands.Reject -> {
                    val output = outputs.noneOrSingle()
                    if (output != null) {
                        requireThat {
                            "Only Buyer Can reject Contract" using (command.signers.first().equals(output.buyer.owningKey))
                        }
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

    fun accept(): ContractState {
        return copy(status = Status.ACCEPTED)
    }

    fun reject(): ContractState {
        return copy(status = Status.REJECTED)
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

@StartableByRPC
class AcceptContractFlow(val stateAndRef: StateAndRef<SalesState>) : FlowLogic<SignedTransaction>() {

    companion object {
        object WAIT_FOR_ACCEPTANCE_CONTRACT : ProgressTracker.Step("Search Contract into ledger")
        object ACCEPT_CONTRACT : ProgressTracker.Step("Sale Contract approved")
        object REJECT_CONTRACT : ProgressTracker.Step("Sale Contract rejected")

        fun tracker() = ProgressTracker(WAIT_FOR_ACCEPTANCE_CONTRACT, ACCEPT_CONTRACT, REJECT_CONTRACT)
    }

    override val progressTracker = AcceptContractFlow.tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        progressTracker.currentStep = AcceptContractFlow.Companion.WAIT_FOR_ACCEPTANCE_CONTRACT

        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val txBuilder = TransactionBuilder(notary = notary)
        txBuilder.addInputState(stateAndRef)

        val saleState = stateAndRef.state.data

        val outputState = saleState.accept()
        val outputContract = SalesContract::class.jvmName
        val outputContractAndState = StateAndContract(outputState, outputContract)
        val contractHash = serviceHub.cordappProvider.getContractAttachmentID(SalesContract::class.jvmName)
        val cmd = Command(SalesContract.SaleCommands.Accept(), serviceHub.myInfo.legalIdentities.first().owningKey)


        // We add the items to the builder.
        if (contractHash != null) {
            txBuilder.withItems(outputContractAndState, cmd, contractHash)
        }

        // Verifying the transaction.
        txBuilder.verify(serviceHub)


        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = AcceptContractFlow.Companion.ACCEPT_CONTRACT

        //seller sign
        val otherpartySession = initiateFlow(saleState.seller)
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherpartySession), CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        return subFlow(FinalityFlow(fullySignedTx))
    }

}


@InitiatingFlow
@StartableByRPC
/*Seller propose Global terme and condition to Buyer*/
class SaleCreateFlow(val buyer: Party,
                     val seller: Party,
                     val hash: String) : FlowLogic<SignedTransaction>() {

    companion object {
        object WAITING_SALE_CONTRACT_PROPOSITION : ProgressTracker.Step("Waiting for proposition")
        object PROPOSAL_FROM_SELLER : ProgressTracker.Step("Seller propose sale contract")

        fun tracker() = ProgressTracker(WAITING_SALE_CONTRACT_PROPOSITION, PROPOSAL_FROM_SELLER)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

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
        return subFlow(FinalityFlow(fullySignedTx))

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
