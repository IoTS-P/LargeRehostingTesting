//package org.iotsplab.akiba.module.server
//
//import io.ktor.http.*
//import io.ktor.server.application.*
//
//abstract class DynamicServer(val name: String, val routePath: String) {
//    var enabled = false
//
//    open suspend fun respondGet(call: ApplicationCall) {
//        if (!enabled) call.respond(HttpStatusCode.BadRequest, null)
//    }
//
//    open suspend fun respondPost(call: ApplicationCall) {
//        if (!enabled) call.respond(HttpStatusCode.BadRequest, null)
//    }
//}