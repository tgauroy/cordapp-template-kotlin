package com.etc.services

import com.etc.contract.states.AcceptContractFlow
import com.etc.contract.states.RejectContractFlow
import com.etc.contract.states.SaleCreateFlow
import com.etc.contract.states.SalesState
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.utilities.getOrThrow
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

// *****************
// * API Endpoints *
// *****************
///etc_api/contract/
@Path("auth")
/*{"username":"tim.taylor@etc.com","password":"Rino123"}*/
class LoginApi {
    data class LoginRequest(
            val username: String,
            val password: String
    )

    data class Role(
            val role: String,
            val valid: Boolean
    )


    data class RoleKeyMapping(
            private var role: String?,
            private var key: String?
    ) {
        @JsonAnySetter
        fun setDynamiValue(name: String, value: String) {
            this.role = name
            this.key = value
        }
    }

    data class EtcToken(
            val roles: List<Role>,
            val corpid: String,
            val uportid: String,
            val expirydate: String?,
            val activesince: String?,
            val state: String
    )

    data class User(
            val userName: String,
            val displayName: String,
            val fullName: String,
            val password: String,
            val etcToken: EtcToken?,
            val signedEtcTokenByCorp: String,
            val roleKeyMapping: RoleKeyMapping?,
            val signedRoleKeyMapping: String,
            val corporateAddress: String,
            val corp: String,
            val branch: String,
            val userType: String
    )

    data class ResponseLogin(
            val user: User,
            val webtoken: String
    )


    @POST
    @Path("login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun authenticate(request: LoginRequest): Response {

        val mapper = jacksonObjectMapper()
        var users = mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue<List<User>>(javaClass.classLoader.getResource("constants/users.json"))
        val user = users.firstOrNull { user ->
            user.userName == request.username
                    && user.password == request.password
        }
        if (null != user) {
            val jwtToken = Jwts.builder()
                    .setSubject(mapper.writeValueAsString(user))
                    .signWith(SignatureAlgorithm.HS512, "Easy Trade Connect")
                    .compact()
            var resp = ResponseLogin(user, jwtToken)
            return Response.status(Response.Status.OK).entity(resp).build()
        } else {
            return Response.status(Response.Status.UNAUTHORIZED).build()
        }

    }


}

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

    @POST
    @Path("rejectSaleContract")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun rejectSaleContractBuyer(contractId: String): Response {


        val results = rpc.vaultQueryBy<SalesState>()

        return try {

            val txState = results.states.firstOrNull { currentState ->
                currentState.ref.txhash.toString().equals(contractId)
            }

            if (txState != null) {
                val id = rpc.startFlow(::RejectContractFlow, txState)
                        .returnValue.getOrThrow()
                Response.status(Response.Status.CREATED).entity(id).build()
            } else {
                return Response.status(Response.Status.NOT_FOUND).build()
            }
        } catch (e: Exception) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }

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