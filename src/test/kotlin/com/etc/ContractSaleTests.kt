package com.etc


import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.testing.ledger
import org.junit.Test
import java.security.KeyPair

class ContractSaleTests {

    val sellerKeyPair = generateKeyPair()
    val buyerKeyPair = generateKeyPair()

    val sellerTest = Party(generateX500NameForSeller(), sellerKeyPair.public)
    val buyerTest = Party(generateX500NameForBuyer(), buyerKeyPair.public)


    fun generateKeyPair(): KeyPair = Crypto.generateKeyPair()

    @Test
    fun salecontract_is_created_with_status_proposed() {
        ledger {
            transaction {
                attachments(SALE_CONTRACT_ID)
                output(SALE_CONTRACT_ID, getDefaultSaleContract())
                command(sellerKeyPair.public) { SalesContract.SaleCommands.Create() }
                this.verifies()
            }
        }
    }

    @Test
    fun salecontract_in_error_if_status_is_not_proposed() {
        val inState = getDefaultSaleContract()
        ledger {
            transaction {
                attachments(SALE_CONTRACT_ID)
                output(SALE_CONTRACT_ID, inState.reject())
                command(sellerKeyPair.public) { SalesContract.SaleCommands.Create() }
                this `fails with` "Contract can only be created with status proposed"

            }
        }
    }

    @Test
    fun buyer_accept_contract_and_status_is_ACCEPTED() {
        val inState = getDefaultSaleContract()
        ledger {
            transaction {
                attachments(SALE_CONTRACT_ID)
                output(SALE_CONTRACT_ID) { inState.accept() }
                command(buyerKeyPair.public) { SalesContract.SaleCommands.Accept() }
                this.verifies()

            }
        }
    }

    @Test
    fun celler_cannot_accept_contract() {
        val inState = getDefaultSaleContract()
        ledger {
            transaction {
                attachments(SALE_CONTRACT_ID)
                output(SALE_CONTRACT_ID) { inState.accept() }
                command(sellerKeyPair.public) { SalesContract.SaleCommands.Accept() }
                this `fails with` "Only Buyer Can accept Contract"

            }
        }
    }

    @Test
    fun buyer_cannot_accept_rejected_contract() {
        val inState = getDefaultSaleContract()
        ledger {
            transaction {
                attachments(SALE_CONTRACT_ID)
                input(SALE_CONTRACT_ID, inState.reject())
                output(SALE_CONTRACT_ID, inState.accept())
                command(sellerKeyPair.public) { SalesContract.SaleCommands.Update() }
                this `fails with` "Cannot update rejected Contract"

            }
        }
    }

    fun generateX500NameForSeller(): CordaX500Name = generateX500Name("TheSeller", "MYCorpSeller", "Paris", "FR")
    fun generateX500NameForBuyer(): CordaX500Name = generateX500Name("TheBuyer", "MYCorpBuyer", "New York", "US")

    fun generateX500Name(commonName: String, organisation: String, locality: String, country: String): CordaX500Name {
        return CordaX500Name(commonName, organisation, locality, country)
    }

    fun getDefaultSaleContract(): SalesState = SalesState(
            buyer = buyerTest,
            seller = sellerTest,
            data_hash = "FakeHASSSSSSSSSSSSSShhh",
            status = SalesState.Status.PROPOSED
    )
}