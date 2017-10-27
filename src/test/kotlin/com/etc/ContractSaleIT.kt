package com.etc


import com.etc.contract.states.SaleCreateFlow
import com.etc.contract.states.SalesState
import io.restassured.RestAssured.given
import io.restassured.http.ContentType.JSON
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import net.corda.core.flows.FlowLogic
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.FlowPermissions.Companion.startFlowPermission
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.nodeapi.User
import net.corda.nodeapi.internal.ServiceInfo
import net.corda.testing.*
import net.corda.testing.driver.driver
import org.hamcrest.core.IsNull
import org.junit.Test
import kotlin.test.assertNotNull


class IntegrationTestSmartLC {

//    companion object InitforTests : NodeBasedTest() {
//
//
//        lateinit var sellerNode: Node
//        lateinit var buyerNode: Node
//        lateinit var notaryNode: Node
//
//        lateinit var sellerWs: WebserverHandle
//
//        val seller = User("seller", "testPassword1", permissions = setOf(
//                startFlowPermission<SaleCreateFlow>()
//        ))
//
//        val buyer = User("buyer", "testPassword1", permissions = setOf(
//                startFlowPermission<SaleCreateFlow>()
//        ))
//
//        @BeforeClass
//        @JvmStatic
//        fun `start nodes needed for tests`() {
//            sellerNode = startNode(ALICE.name, rpcUsers = listOf(seller)).getOrThrow().internals
//            buyerNode = startNode(BOB.name, rpcUsers = listOf(buyer)).getOrThrow().internals
//            notaryNode = startNotaryCluster(DUMMY_NOTARY.name, 1, ValidatingNotaryService.type).getOrThrow().first().internals
//
//        }
//    }

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

    var sellerWebServerPort: Int = 0
    var buyerWebServerPort: Int = 0

    class TestSaleContract {
        var buyer: String? = null
        var seller: String? = null
        var contractData: String? = null
    }

    fun return_sale_Contract_to_create(): TestSaleContract {
        val testSale = TestSaleContract()
        testSale.seller = "Alice Corp"
        testSale.buyer = "Bob Plc"
        testSale.contractData = "data"
        return testSale
    }

    @Test
    fun `should able to contact WebApi Server`() {
        driver {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val (sellerNode) = listOf(
                    startNode(providedName = ALICE.name, rpcUsers = listOf(seller))
            ).transpose().getOrThrow()

            val ws = startWebserver(sellerNode).getOrThrow()
            sellerWebServerPort = ws.listenAddress.port
            assertNotNull(ws.process)
        }
    }

    @Test
    fun `should be able to access to sale Get endpoint`() {

        driver {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val (sellerNode) = listOf(
                    startNode(providedName = ALICE.name, rpcUsers = listOf(seller))
            ).transpose().getOrThrow()

            val ws = startWebserver(sellerNode).getOrThrow()
            sellerWebServerPort = ws.listenAddress.port
            val statusCode = given().baseUri("http://localhost:" + sellerWebServerPort)
                    .contentType(JSON).`when`().get("api/sale/saleGetEndpoint").statusCode()
            assertThat(statusCode, `is`(200))
        }
    }

    @Test
    fun `should throw error if one of PArty not exist during create contract `() {

        val testSale = TestSaleContract()
        testSale.buyer = "Alice Corp"
        testSale.seller = "Not Exist"
        testSale.contractData = "data"

        driver(isDebug = true) {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val buyer = User("buyer", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val (sellerNode, buyerNode, notaryNode) = listOf(
                    startNode(providedName = ALICE.name, rpcUsers = listOf(seller)),
                    startNode(providedName = BOB.name, rpcUsers = listOf(buyer)),
                    startNode(providedName = DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)))
            ).transpose().getOrThrow()

            val ws = startWebserver(sellerNode).getOrThrow()
            sellerWebServerPort = ws.listenAddress.port
            val statusCode = given().baseUri("http://localhost:" + sellerWebServerPort)
                    .contentType(JSON).content(testSale).`when`().post("api/sale/createSaleContract").statusCode()
            assertThat(statusCode, `is`(403))

        }
    }


    @Test
    fun `should be able to create a sale contract from API`() {

        val testSale = return_sale_Contract_to_create()

        driver(isDebug = true) {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val buyer = User("buyer", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val (sellerNode, buyerNode, notaryNode) = listOf(
                    startNode(providedName = ALICE.name, rpcUsers = listOf(seller)),
                    startNode(providedName = BOB.name, rpcUsers = listOf(buyer)),
                    startNode(providedName = DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)))
            ).transpose().getOrThrow()

            val ws = startWebserver(sellerNode).getOrThrow()
            sellerWebServerPort = ws.listenAddress.port
            given().baseUri("http://localhost:" + sellerWebServerPort)
                    .contentType(JSON).content(testSale).`when`().post("api/sale/createSaleContract").then().statusCode(201)


            val sellerClient = sellerNode.rpcClientToNode()
            val sellerProxy: CordaRPCOps = sellerClient.start("seller", "testPassword1").proxy

            val buyerClient = buyerNode.rpcClientToNode()
            val buyerProxy = buyerClient.start("buyer", "testPassword1").proxy

            sellerProxy.waitUntilNetworkReady().getOrThrow()
            buyerProxy.waitUntilNetworkReady().getOrThrow()

            val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)

            val result = sellerProxy.vaultQueryByCriteria(generalCriteria, SalesState::class.java)

            assertThat(result.statesMetadata.first(), `is`(IsNull.notNullValue()))

        }
    }

    @Test
    fun `buyer should be able to accept a sale contract over API`() {

        val testSale = return_sale_Contract_to_create()

        driver {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val buyer = User("buyer", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val (sellerNode, buyerNode, notaryNode) = listOf(
                    startNode(providedName = ALICE.name, rpcUsers = listOf(seller)),
                    startNode(providedName = BOB.name, rpcUsers = listOf(buyer)),
                    startNode(providedName = DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)))
            ).transpose().getOrThrow()

            val wsSeller = startWebserver(sellerNode).getOrThrow()
            sellerWebServerPort = wsSeller.listenAddress.port
            var createResult = given().baseUri("http://localhost:" + sellerWebServerPort)
                    .contentType(JSON).content(testSale).`when`().post("api/sale/createSaleContract").body().jsonPath()

            // "id" : "51FDE36F53DA34FE5050BB042FE2C50E41D9FA0C55EA42AC9C8B38A614D8B8A4",


            var contractId = createResult.get<HashMap<String, String>>("transaction").get("id")

            val wsBuyer = startWebserver(buyerNode).getOrThrow()
            buyerWebServerPort = wsBuyer.listenAddress.port
            given().baseUri("http://localhost:" + buyerWebServerPort)
                    .contentType(JSON).content(contractId).`when`().post("api/sale/acceptSaleContract").then().statusCode(201)

            val sellerClient = sellerNode.rpcClientToNode()
            val sellerProxy: CordaRPCOps = sellerClient.start("seller", "testPassword1").proxy

            val buyerClient = buyerNode.rpcClientToNode()
            val buyerProxy = buyerClient.start("buyer", "testPassword1").proxy

            sellerProxy.waitUntilNetworkReady().getOrThrow()
            buyerProxy.waitUntilNetworkReady().getOrThrow()

            val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)

            val result = sellerProxy.vaultQueryByCriteria(generalCriteria, SalesState::class.java)

            assertThat(result.states.stream().anyMatch { state -> state.state.data.status == (SalesState.Status.ACCEPTED) }, `is`(true))

        }
    }

    @Test
    fun `buyer should be able to reject a sale contract over API`() {

        val testSale = return_sale_Contract_to_create()

        driver {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val buyer = User("buyer", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val (sellerNode, buyerNode, notaryNode) = listOf(
                    startNode(providedName = ALICE.name, rpcUsers = listOf(seller)),
                    startNode(providedName = BOB.name, rpcUsers = listOf(buyer)),
                    startNode(providedName = DUMMY_NOTARY.name, advertisedServices = setOf(ServiceInfo(ValidatingNotaryService.type)))
            ).transpose().getOrThrow()

            val wsSeller = startWebserver(sellerNode).getOrThrow()
            sellerWebServerPort = wsSeller.listenAddress.port
            var createResult = given().baseUri("http://localhost:" + sellerWebServerPort)
                    .contentType(JSON).content(testSale).`when`().post("api/sale/createSaleContract").body().jsonPath()

            // "id" : "51FDE36F53DA34FE5050BB042FE2C50E41D9FA0C55EA42AC9C8B38A614D8B8A4",


            var contractId = createResult.get<HashMap<String, String>>("transaction").get("id")

            val wsBuyer = startWebserver(buyerNode).getOrThrow()
            buyerWebServerPort = wsBuyer.listenAddress.port
            given().baseUri("http://localhost:" + buyerWebServerPort)
                    .contentType(JSON).content(contractId).`when`().post("api/sale/rejectSaleContract").then().statusCode(201)

            val sellerClient = sellerNode.rpcClientToNode()
            val sellerProxy: CordaRPCOps = sellerClient.start("seller", "testPassword1").proxy

            val buyerClient = buyerNode.rpcClientToNode()
            val buyerProxy = buyerClient.start("buyer", "testPassword1").proxy

            sellerProxy.waitUntilNetworkReady().getOrThrow()
            buyerProxy.waitUntilNetworkReady().getOrThrow()

            val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)

            val result = sellerProxy.vaultQueryByCriteria(generalCriteria, SalesState::class.java)

            assertThat(result.states.stream().anyMatch { state -> state.state.data.status == (SalesState.Status.REJECTED) }, `is`(true))

        }
    }


    @Test
    fun `test integration test with sale `() {

        driver {
            val seller = User("seller", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
            ))

            val buyer = User("buyer", "testPassword1", permissions = setOf(
                    startFlowPermission<com.etc.contract.states.SaleCreateFlow>()
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


            (1..1).map { i ->
                sellerProxy.startFlow(::SaleCreateFlow,
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
