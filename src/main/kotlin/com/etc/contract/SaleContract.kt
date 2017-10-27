package com.etc.contract

import com.etc.contract.states.SalesState
import net.corda.core.contracts.*
import net.corda.core.internal.noneOrSingle
import net.corda.core.transactions.LedgerTransaction

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