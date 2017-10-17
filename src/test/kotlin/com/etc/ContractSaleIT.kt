package com.etc



import net.corda.core.flows.FlowLogic
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.node.services.Vault
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.FlowPermissions.Companion.startFlowPermission
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.nodeapi.User
import net.corda.nodeapi.internal.ServiceInfo
import net.corda.testing.*
import net.corda.testing.driver.driver
import org.junit.Test



class IntegrationTestSmartLC {

    inline fun <T : Any, A, B, C, D, E, F, reified R : FlowLogic<T>> CordaRPCOps.startFlow4(
            @Suppress("UNUSED_PARAMETER")
            flowConstructor: (A, B, C, D, E, F) -> R,
            arg0: A,
            arg1: B,
            arg2: C,
            arg3: D,
            arg4: E,
            arg5: F
    ): FlowHandle<T> = startFlowDynamic(R::class.java, arg0, arg1, arg2, arg3, arg4, arg5)


    @Test
    fun `test integration test with smart lc `() {

        driver(isDebug = true) {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<SaleFlow>()
            ))

            val buyer = User("buyer", "testPassword1", permissions = setOf(
                    startFlowPermission<SaleFlow>()
            ))



            val (sellerNode, buyerNode, notaryNode) = listOf(
                    startNode(providedName = ALICE.name, rpcUsers = listOf(seller)),
                    startNode(providedName = BOB.name, rpcUsers = listOf(buyer)),
                    startNode(providedName = DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)))
            ).transpose().getOrThrow()

            val sellerClient = sellerNode.rpcClientToNode()
            val sellerProxy: CordaRPCOps = sellerClient.start("seller", "testPassword1").proxy

            val buyerClient = buyerNode.rpcClientToNode()
            val buyerProxy = buyerClient.start("buyer", "testPassword1").proxy



            sellerProxy.waitUntilNetworkReady().getOrThrow()
            buyerProxy.waitUntilNetworkReady().getOrThrow()


            val sellerVaultUpdate = sellerProxy.vaultTrackBy<SalesState>().updates
            val buyerVaultUpdates = buyerProxy.vaultTrackBy<SalesState>().updates


            val issueRef = OpaqueBytes.of(0)
            val notaryParty = sellerProxy.notaryIdentities().first()
            (1..1).map { i ->
                sellerProxy.startFlow(::SaleFlow,
                        buyerNode.nodeInfo.legalIdentities.first(),
                        sellerNode.nodeInfo.legalIdentities.first(),
                        "Vin&Hash"
                ).returnValue
            }.transpose().getOrThrow()


            sellerVaultUpdate.expectEvents {
                parallel(
                        (1..1).map { i ->
                            expect(
                                    match = { update: Vault.Update<SalesState> ->
                                        update.produced.first().state.data.status == SalesState.Status.PROPOSED
                                    }
                            ) { update ->
                                println("Seller vault update of $update")
                            }
                        }
                )
            }
            buyerVaultUpdates.expectEvents {
                parallel(
                        (1..1).map { i ->
                            expect(
                                    match = { update: Vault.Update<SalesState> ->
                                        update.produced.first().state.data.status == SalesState.Status.PROPOSED
                                    }
                            ) { update ->
                                println("Buyer vault update of $update")
                            }
                        }
                )
            }
        }
    }
}
