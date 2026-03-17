# gop-logging-lib

`pg_core`, `worker`, `backoffice`에서 공통으로 사용하는 구조화 로깅 라이브러리입니다.

이번 리팩토링 기준으로 라이브러리는 **2계층 구조**로 동작합니다.

- Foundation: 로깅 계약/코어 처리(스키마, 마스킹, 에러 변환, JSON 인코딩)
- Bootstrap: Spring Boot 자동 설정(starter)

---

## 1. 모듈 구조

- `gop-logging-contract`
  - 어노테이션(`@LogPrefix`, `@LogSuffix`, `@ArgsLog`, `@ReturnLog`, `@TechnicalMonitored`)
  - 공통 enum/DTO(`LogType`, `LogResult`, `ProcessResult`, `LogEnvelope`, `LogError`)
  - `StructuredLogger` 인터페이스

- `gop-logging-core`
  - `StructuredLoggerImpl`
  - 마스킹(`LogSanitizer`, `MaskingPolicy`)
  - 에러 추출(`ErrorExtractor`): root cause, cause chain, stack hash
  - 크기 정책(`LogSizePolicy`): 일반 8KB / throwable 포함 12KB
  - 인코딩(`LogEncoder`): single-line JSON 보장

- `gop-logging-spring`
  - AOP/필터/TaskDecorator 구현체
  - `StepContextAspect`, `MethodIoLoggingAspect`, `TechnicalLoggingAspect`, `TraceContextFilter`

- `gop-logging-spring-starter`
  - Spring Boot `@AutoConfiguration`
  - 기본 Bean 조립 및 런타임 기본값 제공
  - **서비스에서는 이 모듈만 의존해도 사용 가능**

---

## 2. 핵심 동작 원칙

### 2.1 로그 포맷

- 기본 출력 포맷은 **single-line JSON**입니다.
- 멀티라인 로그 결합 없이 Alloy/Loki에서 안정적으로 수집/파싱할 수 있도록 설계되었습니다.

### 2.2 에러 로깅 확장

`Throwable`이 전달되면 `error` 객체에 아래 정보를 포함합니다.

- `type`, `code`, `message`
- `rootCauseType`, `rootCauseMessage`
- `causeChain` (root 기준 최대 3단계)
- `stackTrace` (제한된 길이)
- `stackHash` (중복 에러 집계/알림 dedupe)
- `stackTruncated`

### 2.3 사이즈 정책

- 일반 로그: 최대 8KB
- `throwable != null` 로그: 최대 12KB
- 초과 시 축약 순서
  1. stack 줄 축소
  2. stack 제거(해시는 유지)
  3. 최소 fallback JSON

---

## 3. 서비스 적용 방법 (권장)

## 3.1 의존성 추가

서비스(`pg_core`, `worker` 등)에서는 아래 **starter 하나**를 추가합니다.

```kotlin
implementation("com.gop.logging:gop-logging-spring-starter:<version>")
```

> Kotlin kapt를 쓰는 프로젝트에서 annotation stub 문제를 방지하려면 아래 설정을 권장합니다.

```kotlin
kapt {
    correctErrorTypes = true
}
```

## 3.2 필수 환경변수

- `LOG_SERVICE_NAME` (필수)
  - 허용값: `pg_core`, `worker`, `backoffice`

## 3.3 선택 환경변수

- `LOG_MIN_LEVEL` (기본: `INFO`)
  - 허용값: `DEBUG`, `INFO`, `WARN`, `ERROR`

- `LOG_RATE_LIMIT_PER_SECOND` (기본: `100`)

- `LOG_TRACE_EXCLUDED_PATH_PREFIXES` (기본: `/actuator,/swagger-ui,/v3/api-docs`)

- `LOG_STRUCTURED_EMITTER` (기본: `SLF4J`)
  - `SLF4J` 또는 `STDOUT_JSON`

## 3.4 Java(Spring Boot) 프로젝트 사용

Java 프로젝트에서도 동일하게 starter 하나로 사용 가능합니다.

```groovy
dependencies {
    implementation "com.gop.logging:gop-logging-spring-starter:<version>"
}
```

Java에서 `StructuredLogger` 호출 시에는 Kotlin 기본 파라미터를 사용할 수 없으므로, 아래처럼 `payload`, `error`를 명시적으로 전달하세요.

```java
structuredLogger.info(LogType.FLOW, LogResult.SUCCESS, Map.of("orderId", "order-123"), null);
```

---

## 4. 사용 예시

## 4.1 일반 구조화 로그

```kotlin
structuredLogger.info(
    logType = LogType.FLOW,
    result = LogResult.SUCCESS,
    payload = mapOf(
        "orderId" to "order-123",
        "processResult" to ProcessResult.SUCCESS.name
    )
)
```

## 4.2 예외 포함 로그

```kotlin
try {
    paymentService.process(command)
} catch (ex: Exception) {
    structuredLogger.error(
        logType = LogType.FLOW,
        result = LogResult.FAIL,
        payload = mapOf("orderId" to command.orderId),
        error = ex
    )
    throw ex
}
```

## 4.3 Method I/O 자동 로깅

```kotlin
@ArgsLog
@ReturnLog
fun confirmPayment(request: ConfirmRequest): ConfirmResponse {
    ...
}
```

- `@ArgsLog`: 진입 시 `DEBUG`, `logType=ARGS`, `result=START`
- `@ReturnLog`: 성공 종료 시 `DEBUG`, `logType=RETURN`, `result=END`
- 예외 종료 시 `result=FAIL` + `error` 정보 포함

## 4.4 기술성능 경고 로깅

```kotlin
@TechnicalMonitored(thresholdMs = 500, step = "payment.confirm")
fun confirm(...) {
    ...
}
```

- 실행시간이 임계값을 넘으면 `TECHNICAL` 로그를 남깁니다.

## 4.5 Java 사용 예시

```java
import com.gop.logging.contract.LogResult;
import com.gop.logging.contract.LogType;
import com.gop.logging.contract.StructuredLogger;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PaymentFacade {
    private final StructuredLogger structuredLogger;

    public PaymentFacade(StructuredLogger structuredLogger) {
        this.structuredLogger = structuredLogger;
    }

    public void confirm(String orderId) {
        structuredLogger.info(
            LogType.FLOW,
            LogResult.START,
            Map.of("orderId", orderId),
            null
        );

        try {
            // business logic
            structuredLogger.info(
                LogType.FLOW,
                LogResult.END,
                Map.of("orderId", orderId, "processResult", "SUCCESS"),
                null
            );
        } catch (RuntimeException ex) {
            structuredLogger.error(
                LogType.FLOW,
                LogResult.FAIL,
                Map.of("orderId", orderId, "processResult", "FAIL"),
                ex
            );
            throw ex;
        }
    }
}
```

---

## 5. 로그 필드 가이드

주요 상위 필드:

- `timestamp`, `level`, `service`, `logType`, `step`, `result`
- `traceId`, `orderFlowId`, `eventId`, `messageId`, `deliveryId`
- `durationMs`, `payloadTruncated`, `suppressed`
- `payload`
- `error`

`error` 예시(개념):

```json
{
  "type": "RuntimeException",
  "code": "PAY-0001",
  "message": "top-level failed",
  "rootCauseType": "SocketTimeoutException",
  "rootCauseMessage": "read timed out",
  "causeChain": [
    { "type": "SocketTimeoutException", "message": "read timed out" },
    { "type": "IOException", "message": "network unstable" },
    { "type": "RuntimeException", "message": "top-level failed" }
  ],
  "stackTrace": "...",
  "stackHash": "3b1e9a6b42d0f1aa",
  "stackTruncated": true
}
```

---

## 6. Alloy / Loki 운영 가이드

- 수집기는 line-by-line 수집을 유지하세요.
- 멀티라인 합치기보다 `json` 파싱을 사용하세요.

### 6.1 수집기 추상화 연결법 (Emitter 선택)

이 라이브러리는 수집기 자체(Alloy/Loki)를 추상화하지 않고, **출력 채널(LogEmitter)** 을 추상화합니다.
즉 앱에서 어디로 어떻게 내보낼지만 통일하고, 수집기는 그 출력을 일관되게 수집하도록 구성합니다.

- `LOG_STRUCTURED_EMITTER=SLF4J` (기본)
  - 앱 로그백/로깅 시스템으로 출력
  - 운영에서 가장 일반적인 방식
- `LOG_STRUCTURED_EMITTER=STDOUT_JSON`
  - 구조화 JSON을 표준 출력으로 직접 내보냄
  - 컨테이너 stdout 수집 파이프라인에 유리

권장 연결 패턴:

1. 앱: single-line JSON 출력 (`SLF4J` 또는 `STDOUT_JSON`)
2. Alloy: line-by-line 수집
3. Alloy pipeline: `json` 파싱 stage 적용
4. Loki: 필드 기반 조회(`traceId`, `error.rootCauseType`, `error.stackHash`)

주의:

- 멀티라인 결합은 기본적으로 불필요합니다.
- Java/Kotlin 서비스가 섞여 있어도 emitter/env 규약을 동일하게 적용하면 운영 일관성을 유지할 수 있습니다.

### 6.2 Alloy 설정 예시

아래 예시는 "파일 로그를 line-by-line로 읽고 JSON 파싱 후 Loki로 전달"하는 기본 형태입니다.

```hcl
local.file_match "app_logs" {
  path_targets = [
    {
      __path__ = "/var/log/app/*.log",
      app      = "pg_core",
      env      = "prod",
    },
  ]
}

loki.source.file "app_logs" {
  targets    = local.file_match.app_logs.targets
  forward_to = [loki.process.app_logs.receiver]
}

loki.process "app_logs" {
  stage.json {
    expressions = {
      timestamp          = "timestamp",
      level              = "level",
      service            = "service",
      logType            = "logType",
      step               = "step",
      result             = "result",
      traceId            = "traceId",
      orderFlowId        = "orderFlowId",
      errorType          = "error.type",
      errorRootCauseType = "error.rootCauseType",
      errorStackHash     = "error.stackHash",
    }
  }

  stage.labels {
    values = {
      app     = "",
      env     = "",
      service = "",
      level   = "",
      logType = "",
    }
  }

  forward_to = [loki.write.default.receiver]
}

loki.write "default" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
```

stdout(JSON) 수집 환경에서는 입력 소스만 바꿔서 동일한 파이프라인을 사용하면 됩니다.

```hcl
loki.source.docker "containers" {
  host       = "unix:///var/run/docker.sock"
  targets    = [{"__address__" = "localhost", "app" = "worker", "env" = "prod"}]
  forward_to = [loki.process.app_logs.receiver]
}
```

라벨 권장:

- 고정/저카디널리티: `app`, `env`, `service`, `level`, `logType`
- 필드 조회 권장: `traceId`, `orderFlowId`, `errorStackHash`, `errorRootCauseType`
- 고카디널리티 값(`traceId`)은 기본적으로 라벨 승격하지 않는 것을 권장합니다.

LogQL 예시:

```logql
{app="pg_core"} | json | traceId="<trace-id>"
```

```logql
{app="worker"} | json | error_rootCauseType="SocketTimeoutException"
```

```logql
{app="pg_core"} | json | error_stackHash="3b1e9a6b42d0f1aa"
```

> 라벨 설계 시 `traceId` 같은 고카디널리티 값은 무분별하게 라벨 승격하지 않는 것을 권장합니다.

---

## 7. 코루틴/비동기 컨텍스트 전파

- 코루틴 경계에서 MDC + StepContext 전파가 필요하면 `MdcStepCoroutineContext.captureCurrent()`를 사용하세요.
- 비동기 실행기(TaskExecutor)는 starter가 `TaskDecorator`를 자동 등록합니다.

---

## 8. 빌드/검증

```bash
./gradlew build
./gradlew check
./gradlew validateLoggingConventions
```

품질 게이트(`validateLoggingConventions`)는 아래를 검사합니다.

- `@LogPrefix("...")` 리터럴 사용 금지 (`StepPrefix.*` 사용 강제)
- `structuredLogger.*(...)`에서 `step =` 직접 주입 금지
- `StepPrefix` 값 형식 검증

---

## 9. 배포 (GitHub Packages)

필수/권장 Gradle 속성:

- `loggingLibVersion` (기본: `1.0.0-SNAPSHOT`)
- `githubPackagesSnapshotUrl` 또는 `githubPackagesReleaseUrl`
- `githubPackagesUser` (기본: `GITHUB_ACTOR`)
- `githubPackagesToken` (기본: `GITHUB_TOKEN`)

배포:

```bash
./gradlew publish
```

버전 suffix 기준 저장소 라우팅:

- `*-SNAPSHOT` -> snapshot URL 우선
- 그 외 -> release URL 우선

---

## 10. 마이그레이션 체크리스트

기존 `gop-logging-spring` 직접 사용 서비스는 아래로 전환하세요.

1. 의존성 정리: `gop-logging-spring-starter`로 통일
2. `LOG_SERVICE_NAME` 설정 확인
3. 기존 로그 대시보드에서 `error.rootCauseType`, `error.stackHash` 필드 활용
4. 배포 후 샘플 요청 기준으로 로그 사이즈/파싱 정상 여부 확인

---

문의/개선 포인트는 `LOGGING_LIB_REFACTOR_GUIDE.md`와 `LOGGING_LIB_REFACTOR_TICKETS.md` 기준으로 이어서 확장합니다.
