# Kotlin JDSL + Spring Data JPA 사용 시 GraalVM Native Image 빌드 이슈 분석

## 소개

본 문서는 과거 Spring Boot 프로젝트에서 `org.springframework.data.jpa.repository.JpaRepository` 인터페이스와 `com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor` 인터페이스를 함께 상속받아 사용하는 리포지토리가 있을 경우, GraalVM 네이티브 이미지 빌드 시 발생했던 문제를 분석합니다. 또한 현재 프로젝트 구성(Spring Boot 3.4.4, Kotlin JDSL 3.5.5, GraalVM Native Build Tools 0.10.6, Kotlin 1.9.25 등)에서 해당 문제가 자연스럽게 해결된 배경을 기술 스택 전반의 개선 관점에서 심층적으로 살펴봅니다.

과거 문제의 핵심은 네이티브 이미지 실행 시 `KotlinJdslJpqlExecutor`가 제공하는 DSL 메소드를 찾을 수 없다는 `NoSuchMethodError` 또는 유사한 런타임 오류였습니다. 이는 주로 Spring Data JPA의 프록시 생성 방식과 GraalVM의 AOT(Ahead-of-Time) 컴파일 간의 상호작용 문제로 인해 발생했습니다.

## 과거 문제점 상세 분석

과거 Spring Boot 환경(주로 3.x 초기 버전)에서 발생했던 문제의 근본 원인은 다음과 같이 복합적으로 추정됩니다:

1.  **Spring Data JPA의 CGLIB 기반 프록시 생성:**
    *   Spring Data JPA는 리포지토리 인터페이스에 대한 구현체를 런타임 시 동적으로 생성하기 위해 Spring AOP를 사용합니다.
    *   `JpaRepository`와 `KotlinJdslJpqlExecutor`를 모두 상속하는 경우처럼, 여러 인터페이스를 구현하거나 클래스 기반 프록시가 필요할 때 **CGLIB** 라이브러리를 사용하여 프록시 객체를 생성할 수 있습니다. 이 프록시 객체는 런타임에 동적으로 바이트코드가 생성됩니다.

2.  **GraalVM AOT 컴파일의 정적 분석 한계:**
    *   GraalVM 네이티브 이미지는 빌드 시점에 **정적 분석(Static Analysis)**을 통해 애플리케이션 코드를 분석하고, 런타임에 필요한 모든 클래스, 메소드, 리소스, 리플렉션 정보, 프록시 정보 등을 미리 결정합니다.
    *   CGLIB와 같이 **런타임에 동적으로 바이트코드를 생성**하는 방식은 정적 분석만으로는 완벽하게 예측하기 어렵습니다. 특히 복잡한 상속 구조(여러 인터페이스를 구현하는 CGLIB 프록시) 생성 시 필요한 리플렉션, 리소스, 프록시 관련 정보를 빌드 시점에 정확히 파악하지 못했을 가능성이 높습니다.

3.  **메타데이터 누락:**
    *   결과적으로, GraalVM AOT 컴파일러가 CGLIB로 생성된 프록시 객체에 `KotlinJdslJpqlExecutor` 인터페이스의 메소드가 포함된다는 사실을 인지하지 못하거나, 관련 리플렉션/프록시 메타데이터를 네이티브 이미지 구성(`native-image.properties` 또는 자동 생성되는 힌트)에 자동으로 포함시키지 못했을 가능성이 큽니다.
    *   이로 인해 네이티브 이미지 실행 시 해당 메소드를 찾지 못하는 `NoSuchMethodError`와 같은 런타임 오류가 발생했습니다.
    *   `reflect-config.json` 등에 관련 클래스(`KotlinJdslJpqlExecutor`, 관련 구현체 등)를 수동으로 등록해도, 프록시 객체 자체의 **동적 생성 특성** 때문에 근본적인 해결이 어려웠을 수 있습니다.

4.  **`BeanPostProcessor`와 AOT 호환성 문제:**
    *   일부 커뮤니티 보고(예: [Stack Overflow](https://stackoverflow.com/questions/77524699/kotlin-jdsl-causes-native-image-build-failure))에 따르면, Kotlin JDSL의 `spring-data-jpa-support` 모듈이 사용하는 `KotlinJdslJpaRepositoryFactoryBeanPostProcessor`와 같은 `BeanPostProcessor`가 Spring AOT 환경과 완전히 호환되지 않았을 가능성이 제기되었습니다. `BeanPostProcessor`는 빈 초기화 과정에 개입하여 빈을 동적으로 수정하거나 프록시를 적용하는데, AOT 환경에서는 이러한 동적 처리에 제약이 있을 수 있습니다. Spring AOT는 빌드 시점에 빈 정의를 확정하려고 시도하기 때문입니다.

## 현재 프로젝트에서의 해결 분석: 누적된 개선 효과

현재 프로젝트 구성에서는 과거 문제가 발생하지 않습니다. 이는 특정 하나의 수정 때문이라기보다는, 관련 기술 스택 전반에 걸쳐 이루어진 **지속적인 개선 사항들의 누적된 효과** 덕분으로 판단됩니다. 주요 개선 요인은 다음과 같습니다:

1.  **Spring Framework (6.0.x -> 6.1.x+)의 AOT 엔진 성숙:**
    *   **Spring Framework 6.0**은 AOT 변환을 위한 포괄적인 기반을 마련하고 네이티브 이미지 지원을 일반 기능으로 승격시켰습니다. AOT 처리 지원을 위한 `refreshForAotProcessing` 메소드, AOP 프록시 및 구성 클래스에 대한 초기 프록시 클래스 결정 지원, JPA 관리 유형 사전 결정 지원 등이 도입되었습니다.
    *   **Spring Framework 6.1** 이후 버전에서는 AOT 엔진의 **메타데이터 자동 감지 및 생성 능력**이 더욱 크게 향상되었습니다. 복잡한 프록시 객체(예: 여러 인터페이스를 구현하는 CGLIB 프록시) 생성 시 필요한 리플렉션, 리소스, 프록시 관련 정보를 더 정확하게 예측하고 빌드 힌트로 제공하는 능력이 정교해졌습니다.
    *   `RuntimeHintsRegistrar` 인터페이스 도입으로 라이브러리(예: Spring Data JPA)가 AOT 엔진에게 필요한 런타임 정보(리플렉션, 프록시, 리소스 힌트 등)를 프로그래밍 방식으로 더 명확하게 전달할 수 있게 되었습니다.
    *   조건부 구성 처리, AOP 프록시 처리 등 네이티브 이미지 빌드를 위한 전반적인 지원이 강화되었으며, Java 17 베이스라인 및 가상 스레드 지원 등도 안정성 향상에 기여했을 수 있습니다.

2.  **GraalVM Native Build Tools (NBT) 개선 (0.10.2 -> 0.10.6):**
    *   NBT 플러그인의 지속적인 개선을 통해 Spring Boot AOT 엔진과의 통합이 강화되고, 생성된 메타데이터를 더 잘 이해하고 활용하게 되었습니다.
    *   **버전별 주요 개선 사항 요약:**
        *   `0.10.2`: 메타데이터 복사 작업 기본 대상 디렉토리 업데이트 등.
        *   `0.10.3`: 메타데이터 리포지토리 버전 업데이트, 문서 개선.
        *   `0.10.4`: JUnit Platform `@EnabledOnOs` 문제 해결, 주요 JDK 버전 감지 개선.
        *   `0.10.5`: GraalVM 버전 검사 개선, Maven 종속성 업그레이드.
        *   `0.10.6`: SBOM(Software Bill of Materials) 관련 문제 수정(해결되지 않은 아티팩트 및 빈 "components"), reachability 메타데이터 리포지토리 버전 업데이트.
    *   이러한 개선은 메타데이터 처리 로직 개선, 의존성 분석 정확도 향상, 빌드 프로세스 안정성 증가 등으로 이어져 Kotlin/Spring Boot 프로젝트의 네이티브 빌드 성공률을 높였습니다.

3.  **Kotlin 및 Kotlin JDSL 생태계 발전:**
    *   **Kotlin (1.9.21 -> 1.9.25):** Kotlin 컴파일러(K2 포함) 및 관련 Gradle 플러그인의 지속적인 개선이 이루어졌습니다. Kotlin/Native 메모리 관리자 성능 개선, Kotlin Multiplatform 지원 강화 등 생태계 전반의 안정성 향상 노력이 있었습니다. Kotlin 컴파일러 자체를 GraalVM 네이티브 이미지와 호환되도록 만드는 작업([KT-66666](https://youtrack.jetbrains.com/issue/KT-66666)) 등 GraalVM 호환성 향상 노력도 간접적으로 긍정적인 영향을 미쳤을 수 있습니다.
    *   **Kotlin JDSL (과거 버전 -> 3.5.5):** 라이브러리 내부적으로 GraalVM 호환성을 위한 메타데이터를 더 잘 제공하도록 수정되었거나, 앞서 언급된 `KotlinJdslJpaRepositoryFactoryBeanPostProcessor`의 사용 방식 등 내부 구현이 AOT 환경에 더 친화적으로 변경되었을 가능성이 있습니다. 관련 이슈([#397](https://github.com/line/kotlin-jdsl/issues/397) - Java 8 호환성, [#668](https://github.com/line/kotlin-jdsl/issues/668) - 사용자 정의 리포지토리 구현 문제) 등이 해결되면서 전반적인 안정성이 향상되었을 수 있습니다. (구체적인 AOT 관련 변경 내역은 확인 어려움)

4.  **Spring Boot 자체 개선 (3.2.4 -> 3.4.4):**
    *   앞서 언급된 Spring Framework 개선 사항 통합 외에도, Spring Boot 레벨에서 네이티브 이미지 관련 구성 및 지원이 꾸준히 업데이트되었습니다.
    *   **Spring Boot 3.2:** Kotlin Gradle 플러그인 1.9.0 버그(AOT 처리 시 리소스 누락) 관련 주의 사항 언급 및 후속 버전에서의 해결. 기본 CNB(Cloud Native Buildpacks) 빌더 업그레이드.
    *   **Spring Boot 3.4:** Netty 네이티브 이미지 관련 문제 해결을 위한 메타데이터 업그레이드 필요성 언급. Spring Security의 `@AuthorizeReturnObject`, `@PreAuthorize`/`@PostAuthorize` 콜백 등 네이티브 이미지 호환성 개선.
    *   `org.springframework.data.jpa.repository.aot` 패키지의 존재는 Spring Data JPA 팀이 네이티브 이미지 환경에서의 리포지토리 동작 최적화(프록시 처리 포함)를 위해 지속적으로 노력하고 있음을 시사합니다.

## 커뮤니티 사례 및 관련 이슈

과거 유사한 문제가 커뮤니티에서도 보고 및 논의되었습니다:

-   **Stack Overflow 질문:** [kotlin-jdsl-causes-native-image-build-failure](https://stackoverflow.com/questions/77524699/kotlin-jdsl-causes-native-image-build-failure) 에서 `KotlinJdslJpaRepositoryFactoryBeanPostProcessor`가 문제 원인으로 지목되며 `BeanPostProcessor`와 Spring AOT 간의 비호환성 가능성을 시사했습니다.
-   **GitHub 이슈:**
    *   **Spring Framework [#31618](https://github.com/spring-projects/spring-framework/issues/31618):** "Kotlin DSL을 사용한 GraalVM 네이티브 오류"로 보고되었으며, 유사 문제 인지를 보여줍니다.
    *   **Kotlin JDSL [#397](https://github.com/line/kotlin-jdsl/issues/397), [#668](https://github.com/line/kotlin-jdsl/issues/668):** Spring Data JPA 호환성 및 사용자 정의 리포지토리 관련 문제가 논의되었습니다.
    *   **GraalVM [#722](https://github.com/oracle/graal/issues/722):** Kotlin 애플리케이션의 객체 직렬화 관련 네이티브 이미지 빌드 문제가 보고되어, Kotlin과 GraalVM 통합의 복잡성을 보여줍니다.

이러한 사례들은 문제 해결이 특정 라이브러리 하나의 수정보다는 기술 스택 전반의 개선을 통해 이루어졌을 가능성을 뒷받침합니다.

## 결론

과거 Spring Boot 3.x 초기 환경에서 발생했던 Kotlin JDSL과 Spring Data JPA 리포지토리를 함께 사용할 때의 GraalVM 네이티브 이미지 빌드 문제는, 특정 한두 가지 수정 때문이라기보다는 **Spring Framework AOT 엔진의 성숙, GraalVM Native Build Tools의 발전, Kotlin 및 Kotlin JDSL 생태계의 개선, 그리고 Spring Boot 자체의 지속적인 네이티브 지원 강화**라는 여러 요인이 복합적으로 작용한 결과로 해결된 것으로 보입니다.

특히 Spring Framework의 AOT 엔진이 CGLIB 기반의 복잡한 프록시 시나리오를 더 잘 이해하고 필요한 메타데이터를 자동으로 생성하는 능력이 크게 향상되었으며, 관련 빌드 도구 및 라이브러리들도 이에 발맞춰 개선되었습니다. 현재 기술 스택(Spring Boot 3.4.4, NBT 0.10.6, Kotlin 1.9.25, Kotlin JDSL 3.5.5 등)에서는 프레임워크와 빌드 도구가 이러한 복잡한 시나리오를 더 잘 이해하고 필요한 메타데이터를 자동으로 처리해주기 때문에, 개발자가 별도의 복잡한 수동 구성 없이도 성공적으로 네이티브 이미지를 빌드할 수 있게 되었습니다.

## 권장 사항 및 모범 사례

-   **최신 버전 유지:** 네이티브 이미지 호환성 관련 최신 개선 사항과 버그 수정을 활용하기 위해 Spring Boot, Spring Framework, Kotlin, Kotlin JDSL, NBT 등 기술 스택의 버전을 가능한 최신 안정 버전으로 유지하는 것이 좋습니다.
-   **표준 도구 활용:** Spring Boot Native Profile 및 GraalVM Native Build Tools Gradle/Maven 플러그인을 적극 활용하여 빌드 프로세스를 간소화하고 모범 사례를 따르는 것이 좋습니다.
-   **Runtime Hints 활용 (필요시):** 자동 메타데이터 감지가 어려운 특수한 경우나 타사 라이브러리 관련 문제가 발생하면, Spring Framework의 `RuntimeHintsRegistrar` 메커니즘을 사용하여 필요한 힌트(리플렉션, 프록시 등)를 직접 제공하는 것을 고려할 수 있습니다.
-   **공식 문서 참조:** 각 기술의 공식 문서를 주기적으로 확인하여 네이티브 이미지 지원 현황, 알려진 제약 사항, 권장 구성 방법 등에 대한 최신 정보를 얻는 것이 중요합니다.
