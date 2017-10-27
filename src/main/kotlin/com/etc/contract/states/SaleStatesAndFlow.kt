package com.etc.contract.states

import co.paralleluniverse.fibers.Suspendable
import com.etc.contract.SalesContract
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import kotlin.reflect.jvm.jvmName

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

@InitiatedBy(SaleCreateFlow::class)
class SaleCreateResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession, tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an Sale transaction." using (output is SalesState)
            }
        }

        subFlow(signTransactionFlow)
    }
}

@StartableByRPC
class RejectContractFlow(val stateAndRef: StateAndRef<SalesState>) : FlowLogic<SignedTransaction>() {

    companion object {
        object WAIT_FOR_ACCEPTANCE_CONTRACT : ProgressTracker.Step("Search Contract into ledger")
        object REJECT_CONTRACT : ProgressTracker.Step("Sale Contract rejected")

        fun tracker() = ProgressTracker(WAIT_FOR_ACCEPTANCE_CONTRACT, REJECT_CONTRACT)
    }

    override val progressTracker = RejectContractFlow.tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        progressTracker.currentStep = RejectContractFlow.Companion.WAIT_FOR_ACCEPTANCE_CONTRACT

        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val txBuilder = TransactionBuilder(notary = notary)
        txBuilder.addInputState(stateAndRef)

        val saleState = stateAndRef.state.data

        val outputState = saleState.reject()
        val outputContract = SalesContract::class.jvmName
        val outputContractAndState = StateAndContract(outputState, outputContract)
        val contractHash = serviceHub.cordappProvider.getContractAttachmentID(SalesContract::class.jvmName)
        val cmd = Command(SalesContract.SaleCommands.Reject(), serviceHub.myInfo.legalIdentities.first().owningKey)


        // We add the items to the builder.
        if (contractHash != null) {
            txBuilder.withItems(outputContractAndState, cmd, contractHash)
        }

        // Verifying the transaction.
        txBuilder.verify(serviceHub)


        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = RejectContractFlow.Companion.REJECT_CONTRACT

        //seller sign
        val otherpartySession = initiateFlow(saleState.seller)
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherpartySession), CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        return subFlow(FinalityFlow(fullySignedTx))
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