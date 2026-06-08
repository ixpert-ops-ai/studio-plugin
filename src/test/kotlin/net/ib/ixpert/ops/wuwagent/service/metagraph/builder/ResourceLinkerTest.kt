package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ApiEndpoint
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Test
import kotlin.test.assertTrue

class ResourceLinkerTest {

    @Test
    fun `test Vue to Controller URL binding matching (member-market scenario)`() {
        // 1. Mock Java Nodes (Controllers)
        val productController = FileNode(
            path = "c:/Workspace/member-market/src/main/java/com/market/controller/ProductController.java",
            className = "ProductController",
            packageName = "com.market.controller",
            fileType = SpringFileType.REST_CONTROLLER,
            layer = ArchitectureLayer.PRESENTATION,
            isInterface = false,
            apiEndpoints = listOf(
                ApiEndpoint(httpMethod = "GET", path = "/api/products", handlerMethod = "getProducts"),
                ApiEndpoint(httpMethod = "GET", path = "/api/products/{id}", handlerMethod = "getProduct"),
                ApiEndpoint(httpMethod = "GET", path = "/api/products/me", handlerMethod = "getMyProducts")
            )
        )

        val chatController = FileNode(
            path = "c:/Workspace/member-market/src/main/java/com/market/controller/ChatController.java",
            className = "ChatController",
            packageName = "com.market.controller",
            fileType = SpringFileType.REST_CONTROLLER,
            layer = ArchitectureLayer.PRESENTATION,
            isInterface = false,
            apiEndpoints = listOf(
                ApiEndpoint(httpMethod = "GET", path = "/api/chats", handlerMethod = "getChats"),
                ApiEndpoint(httpMethod = "GET", path = "/api/chats/{roomId}/messages", handlerMethod = "getMessages")
            )
        )

        val javaNodes = mapOf(
            productController.path to productController,
            chatController.path to chatController
        )

        val resourceLinker = ResourceLinker(javaNodes)

        // 2. Mock Vue Resource Nodes
        // baseURL이 선언된 설정 파일
        val apiIndexJs = ResourceNode(
            path = "c:/Workspace/member-market/frontend/src/api/index.js",
            type = ResourceType.SCRIPT,
            layer = "PRESENTATION",
            linkedTo = emptyList(),
            linkType = "",
            metadata = mapOf("base_url" to listOf("http://localhost:8080/api"))
        )

        // ProductDetailView (다중 매칭 케이스: 상품 정보 + 채팅 정보)
        val productDetailView = ResourceNode(
            path = "c:/Workspace/member-market/frontend/src/views/ProductDetailView.vue",
            type = ResourceType.VIEW,
            layer = "PRESENTATION",
            linkedTo = emptyList(),
            linkType = "",
            metadata = mapOf("api_url" to listOf("/products/\${route.params.id}", "/chats"))
        )

        // ChatRoomView (Path Variable 케이스)
        val chatRoomView = ResourceNode(
            path = "c:/Workspace/member-market/frontend/src/views/ChatRoomView.vue",
            type = ResourceType.VIEW,
            layer = "PRESENTATION",
            linkedTo = emptyList(),
            linkType = "",
            metadata = mapOf("api_url" to listOf("/chats/\${roomId}/messages"))
        )

        // MyProductListView (Prefix 매칭 케이스)
        val myProductListView = ResourceNode(
            path = "c:/Workspace/member-market/frontend/src/views/MyProductListView.vue",
            type = ResourceType.VIEW,
            layer = "PRESENTATION",
            linkedTo = emptyList(),
            linkType = "",
            metadata = mapOf("api_url" to listOf("/products/me"))
        )

        // 3. 실행
        val resourceNodes = listOf(apiIndexJs, productDetailView, chatRoomView, myProductListView)
        val linkedNodes = resourceLinker.link(resourceNodes)

        // 4. 검증
        val linkedProductDetail = linkedNodes.find { it.path == productDetailView.path }!!
        assertTrue(linkedProductDetail.linkedTo.contains(productController.path), "ProductDetailView should link to ProductController")
        assertTrue(linkedProductDetail.linkedTo.contains(chatController.path), "ProductDetailView should link to ChatController")

        val linkedChatRoom = linkedNodes.find { it.path == chatRoomView.path }!!
        assertTrue(linkedChatRoom.linkedTo.contains(chatController.path), "ChatRoomView should link to ChatController")

        val linkedMyProductList = linkedNodes.find { it.path == myProductListView.path }!!
        assertTrue(linkedMyProductList.linkedTo.contains(productController.path), "MyProductListView should link to ProductController")
    }
}
