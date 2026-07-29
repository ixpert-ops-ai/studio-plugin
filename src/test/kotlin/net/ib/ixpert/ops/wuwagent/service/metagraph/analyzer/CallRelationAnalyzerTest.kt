package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.RelationshipType
import org.junit.Test

class CallRelationAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    @Test
    fun testUsesTypeExtraction() {
        // Framework classes
        myFixture.addClass("package org.springframework.http; public class ResponseEntity<T> {}")
        myFixture.addClass("package org.springframework.data.domain; public class Page<T> {}")
        
        // Project internal classes
        myFixture.addClass("package com.membermarket.api.common; public class ApiResponse<T> {}")
        myFixture.addClass("package com.membermarket.api.product.dto; public class ProductCreateRequest {}")
        myFixture.addClass("package com.membermarket.api.product.dto; public class ProductResponse {}")
        myFixture.addClass("package com.membermarket.api.product.dto; public class ProductListResponse {}")
        myFixture.addClass("package com.membermarket.security; public class UserDetails {}")
        
        val javaCode = """
            package com.membermarket.api.product;
            
            import org.springframework.http.ResponseEntity;
            import org.springframework.data.domain.Page;
            import com.membermarket.api.common.ApiResponse;
            import com.membermarket.api.product.dto.ProductCreateRequest;
            import com.membermarket.api.product.dto.ProductResponse;
            import com.membermarket.api.product.dto.ProductListResponse;
            import com.membermarket.security.UserDetails;
            
            public class ProductController {
                public ResponseEntity<ApiResponse<Page<ProductListResponse>>> getProducts() { return null; }
                public ResponseEntity<ApiResponse<ProductResponse>> getProduct(Long id) { return null; }
                public ResponseEntity<ApiResponse<Long>> createProduct(ProductCreateRequest request, UserDetails userDetails) { return null; }
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("ProductController.java", javaCode)
        val psiClass = (psiFile as com.intellij.psi.PsiJavaFile).classes.find { it.name == "ProductController" }!!
        
        // In LightJavaCodeInsightFixtureTestCase, files are created under /src
        // Let's assume project base path is /src
        // And we filter out anything outside /src/com/membermarket
        // Wait, if we use /src, EVERYTHING is considered internal project!
        // So we must use "/src/com/membermarket" as project base path.
        // Wait, CallRelationAnalyzer checks: targetVirtualFile.path.startsWith(projectBasePath)
        
        val analyzer = CallRelationAnalyzer()
        val relationships = analyzer.analyze(psiClass, "/src/com/membermarket")
        
        val usesTypes = relationships.filter { it.type == RelationshipType.USES_TYPE }.map { it.target }
        println("--- EXTRACTED USES_TYPE TARGETS ---")
        usesTypes.forEach { println("- " + it) }
        println("-----------------------------------")
    }

    @Test
    fun testEdgeCasesForAnyframe() {
        myFixture.addClass("package java.util; public interface List<E> {}")
        myFixture.addClass("package java.util; public interface Map<K, V> {}")
        myFixture.addClass("package org.springframework.http; public class ResponseEntity<T> {}")
        myFixture.addClass("package org.springframework.data.domain; public class Page<T> {}")
        
        myFixture.addClass("package com.anyframe.project.vo; public class ProductVO {}")
        myFixture.addClass("package com.anyframe.project.vo; public class CategoryVO {}")
        myFixture.addClass("package com.anyframe.project.vo; public class ErrorVO {}")
        myFixture.addClass("package com.anyframe.project.vo; public class ProductDto {}")
        myFixture.addClass("package com.anyframe.project.vo; public class ProductResponse {}")
        myFixture.addClass("package com.anyframe.project.vo; public class ProductEntity {}")
        
        val javaCode = """
            package com.anyframe.project.biz;
            
            import java.util.List;
            import java.util.Map;
            import org.springframework.http.ResponseEntity;
            import org.springframework.data.domain.Page;
            
            import com.anyframe.project.vo.ProductVO;
            import com.anyframe.project.vo.CategoryVO;
            import com.anyframe.project.vo.ErrorVO;
            import com.anyframe.project.vo.ProductDto;
            import com.anyframe.project.vo.ProductResponse;
            import com.anyframe.project.vo.ProductEntity;
            // MissingUnresolvedType is explicitly missing
            
            public class ProductBiz {
                // 1. void return, empty params
                public void doSomething() {}
                
                // 2. primitive return, primitive params
                public int calculateSum(int a, double b) { return 0; }
                
                // 3. Generic return: List<ProductVO>
                public List<ProductVO> findProducts() { return null; }
                
                // 4. Array return: ProductVO[]
                public ProductVO[] getProductArray() { return null; }
                
                // 5. Wildcard generic: List<? extends CategoryVO>
                public void processCategories(List<? extends CategoryVO> categories) {}
                
                // 6. Unresolved type
                public MissingUnresolvedType getMissing() { return null; }
                
                // 7. Normal VO return
                public ErrorVO createError() { return null; }
                
                // 8. Nested Generics (Map<String, List<ProductDto>>)
                public Map<String, List<ProductDto>> getProductDtoMap() { return null; }
                
                // 9. Generic Wrapper (ResponseEntity<ProductResponse>)
                public ResponseEntity<ProductResponse> getProductResponse() { return null; }
                
                // 10. Spring Data Page (Page<ProductEntity>)
                public Page<ProductEntity> getProductEntities() { return null; }
                
                // 11. Deeply Nested (ResponseEntity<List<Map<String, ProductVO>>>)
                public ResponseEntity<List<Map<String, ProductVO>>> getDeeplyNested() { return null; }
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("ProductBiz.java", javaCode)
        val psiClass = (psiFile as com.intellij.psi.PsiJavaFile).classes.find { it.name == "ProductBiz" }!!
        
        val analyzer = CallRelationAnalyzer()
        val relationships = analyzer.analyze(psiClass, "/src/com/anyframe/project")
        
        val usesTypes = relationships.filter { it.type == RelationshipType.USES_TYPE }.map { it.target }
        println("--- EDGE CASES EXTRACTED USES_TYPE TARGETS ---")
        usesTypes.forEach { println("- " + it) }
        println("----------------------------------------------")
        
        assertTrue(usesTypes.contains("vo/ProductVO.java"))
        assertTrue(usesTypes.contains("vo/CategoryVO.java"))
        assertTrue(usesTypes.contains("vo/ErrorVO.java"))
        assertTrue(usesTypes.contains("vo/ProductDto.java"))
        assertTrue(usesTypes.contains("vo/ProductResponse.java"))
        assertTrue(usesTypes.contains("vo/ProductEntity.java"))
    }
}
