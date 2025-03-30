# Kotlin JDSL + Spring Data JPA 사용 시 GraalVM Native Image 빌드 이슈 및 해결

## 과거 발생했던 문제

Spring Boot 프로젝트에서 `org.springframework.data.jpa.repository.JpaRepository` 인터페이스와 `com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor` 인터페이스를 함께 상속받아 사용하는 리포지토리가 있을 경우, **과거 Spring Boot 3.x 초기 버전을 포함한 환경**에서 `./gradlew nativeCompile` 명령어로 GraalVM 네이티브 이미지를 빌드할 때 다음과 같은 문제가 발생할 수 있었습니다:

1.  **오류 내용:** 네이티브 이미지 실행 시, `KotlinJdslJpqlExecutor`가 제공하는 DSL 메소드(예: `findAll`, `findOne` 등)를 찾을 수 없다는 런타임 오류 (주로 `MethodNotFoundException` 또는 관련 프록시 오류) 발생.
2.  **원인:**
    *   Spring Data JPA가 CGLIB를 사용하여 동적으로 생성하는 리포지토리 프록시 클래스에 `KotlinJdslJpqlExecutor`의 메소드 정보가 GraalVM의 AOT(Ahead-of-Time) 컴파일 시점에 제대로 반영되지 않음.
    *   필요한 리플렉션 또는 프록시 관련 메타데이터가 네이티브 이미지 빌드 구성에 자동으로 포함되지 않음.
3.  **시도했던 해결 방법 및 한계:**
    *   `reflect-config.json` 파일에 관련 클래스(예: `KotlinJdslJpqlExecutor`, 관련 구현체 등)를 수동으로 등록해도 프록시 생성 방식의 근본적인 문제로 인해 해결되지 않는 경우가 많았음.
    *   Spring Boot 설정에서 JDK 동적 프록시를 사용하도록 강제(`spring.aop.proxy-target-class=false` 등)하면 이 문제는 해결될 수 있었으나, 프로젝트 전반적으로 CGLIB 기반 프록시를 사용하지 못하게 되어 다른 기능에 영향을 줄 수 있는 부작용이 있었음.

## 현재 프로젝트에서의 해결 분석 (문제 미발생)

현재 프로젝트 구성(Spring Boot 3.4.4, Kotlin JDSL 3.5.5, GraalVM Native Build Tools 0.10.6, Kotlin 1.9.25 등)에서는 `./gradlew nativeCompile`을 통해 네이티브 이미지를 빌드했을 때 과거 발생했던 문제가 나타나지 않았습니다. 웹 검색 및 관련 릴리스 노트를 검토한 결과, 이 문제를 해결한 **단일 특정 변경 사항을 지목하기는 어렵습니다.**

대신, 과거 문제가 발생했던 시점(예: Spring Boot 3.2.4, NBT 0.10.2, Kotlin 1.9.21 등)과 현재 시점 사이의 **기술 스택 전반에 걸친 지속적인 개선의 누적된 결과**로 문제가 해결된 것으로 강력히 추정됩니다. 주요 개선 요인은 다음과 같습니다:

1.  **Spring Framework의 AOT 엔진 성숙도 향상 (Spring Boot 3.2 ~ 3.4 기간):**
    *   Spring Boot가 의존하는 Spring Framework(특히 6.1.x 버전 이후)의 AOT 처리 엔진이 크게 개선되었습니다.
    *   복잡한 프록시 객체(예: `JpaRepository` + `KotlinJdslJpqlExecutor` 조합) 생성 시 필요한 리플렉션, 리소스, 프록시 관련 **메타데이터 자동 감지 및 생성 능력**이 더욱 정교해졌습니다.
    *   조건부 구성 처리 등 네이티브 이미지 빌드를 위한 전반적인 지원이 강화되었습니다.

2.  **GraalVM Native Build Tools (NBT) 개선 (0.10.2 ~ 0.10.6 기간):**
    *   NBT 플러그인 자체의 개선을 통해 Spring Boot AOT 엔진과의 통합, 메타데이터 형식 표준화, 빌드 프로세스 안정성 등이 향상되었습니다.
    *   내부적으로 메타데이터 처리 로직이 개선되어 복잡한 의존성 구조를 더 잘 처리하게 되었을 가능성이 있습니다.

3.  **Kotlin 및 Kotlin JDSL 생태계 개선:**
    *   Kotlin 컴파일러 및 관련 Gradle 플러그인(1.9.21 -> 1.9.25)의 지속적인 개선.
    *   Kotlin JDSL 라이브러리(과거 버전 -> 3.5.5) 자체적으로 GraalVM 호환성을 위한 메타데이터 제공 또는 내부 구현 방식이 개선되었을 수 있습니다.

4.  **Spring Boot 자체의 개선 (3.2.4 -> 3.4.4):**
    *   앞서 언급된 Spring Framework 개선 사항 통합 외에도, Spring Boot 레벨에서의 네이티브 이미지 관련 구성 및 지원이 꾸준히 업데이트되었습니다.

## 결론

과거 Spring Boot 3.x 환경에서 발생했던 Kotlin JDSL과 Spring Data JPA 리포지토리를 함께 사용할 때의 GraalVM 네이티브 이미지 빌드 문제는, 특정 한두 가지 수정 때문이라기보다는 **Spring Framework AOT 엔진, GraalVM Native Build Tools, Kotlin 생태계, 그리고 Spring Boot 자체의 지속적인 개선과 성숙** 덕분에 해결된 것으로 보입니다. 현재 기술 스택(Spring Boot 3.4.4, NBT 0.10.6 등)에서는 프레임워크와 빌드 도구가 이러한 복잡한 시나리오를 더 잘 이해하고 필요한 메타데이터를 자동으로 처리해주기 때문에, 별도의 복잡한 구성 없이도 성공적으로 네이티브 이미지를 빌드할 수 있습니다.
