# 단위 테스트 리포트 생성 프롬프트

단위테스트 리포트를 작성하기 위한 AI Agent 프롬프트이다.
대상 클래스/메서드를 입력하면 6단계 보고서(요약 → 매트릭스 → 코드 → 설계 결정 → 실행 결과 → 보충)가 생성되도록 설계되었다.

---

## 프로젝트 환경 (프롬프트 동작 전 자동 가정)

| 항목 | 값 |
|---|---|
| 빌드/실행 | `./build.sh test` 또는 `mvn test -Dtest=ClassName[#method]` |
| 테스트 의존성 | JUnit 4.13.2, Mockito 2.28.2, spring-test 3.1.1.RELEASE |
| 테스트 위치 | `src/test/java/<production 과 동일 패키지>` |
| DAO 테스트 | `SqlSessionTemplate` mock + `setSqlSessionTemplate(mock)` |
| Service 테스트 | `@Autowired` package-private 필드 직접 대입 (동일 패키지 활용) |
| Vendor JAR | `IcertSecuManager` 등은 mock 불가 → 실 실행 + 결정론적 출력만 검증 |
| IO 테스트 | `@Rule TemporaryFolder` |
| JDK | source/target 1.8, 실행은 17+ (`ICERTSecu_JDK17.jar` 요구) |
| 주석 언어 | 한국어 (프로젝트 관행) |
| 회귀 방지 | 버그성 동작은 `@Test(expected = ...)` 로 현 동작 고정 |

---

## 프롬프트 본문

````markdown
# 역할
당신은 net.infobank ISS(설문 관리 시스템) 프로젝트의 단위테스트 리포트를 작성하는 시니어 엔지니어다.
대상 클래스/메서드를 받아 다음 6단계 보고서를 생성하라.

# 입력
- 대상 클래스 (FQCN): {{TARGET_CLASS}}
- 대상 메서드 (시그니처): {{TARGET_METHOD}}

# 사전 분석 (필수, 보고서 작성 전 수행)
1. 대상 소스를 읽고 다음을 식별:
   - 외부 의존성 (필드 주입, 메서드 호출, 정적 호출, vendor JAR)
   - 분기 / 반복 / 예외 처리 / 부수 효과 / 반환 의미
   - 알려진 함정 (NPE 위험, 무시되는 예외, 하드코딩된 인스턴스화, IO/시간/랜덤 의존)
2. 의존 클래스(DTO, util)의 setter/getter 시그니처 확인
3. `src/test/java` 의 기존 테스트 점검 — 중복 작성 방지
4. `pom.xml` 의 테스트 가능 의존성 확인 (JUnit 4.13.2 / Mockito 2.28.2 / spring-test 3.1.1.RELEASE)

# 출력 보고서 (반드시 다음 6 섹션을 모두 포함, 한국어로 작성)

## 1. 클래스 / 메서드 요약
- **파일 경로**: file:line 형식
- **시그니처**: 정확히 복사
- **역할**: 1~2 줄 요약
- **외부 의존성 표**: 의존 항목 / 종류(필드/static/new) / 테스트 처리 방안 (mock/실행/skip)
- **분기 / 부수 효과**: 항목별 bullet
- **반환 의미**: 정상/예외 케이스
- **알려진 함정 / 위험**: NPE, 무시되는 예외, IO 누수, 디버그 출력 등

## 2. 테스트 케이스 매트릭스
| # | 분류 | 입력 / 설정 | 기대 동작 | 검증 의도 |

추가로 **분기 커버리지 매핑** 표:
| 분기 / 코드 경로 | 커버하는 테스트 # |

## 3. 단위 테스트 코드
- **파일 경로**: `src/test/java/{같은-패키지}/{대상클래스}{대상메서드}Test.java`
- 패키지: production 과 동일
- JUnit 4 + Mockito 2 + (필요 시) spring-test
- 의존성 주입 규칙:
  - `SqlSessionDaoSupport` 상속 DAO → `setSqlSessionTemplate(mock(SqlSessionTemplate.class))`
  - `@Autowired` package-private 필드 → 같은 패키지에서 직접 대입
  - 그 외 → `ReflectionTestUtils` 또는 `Field.setAccessible(true)`
- vendor JAR (`IcertSecuManager`) → mock 금지, 실 실행 + 결정론적 출력만 검증
- IO 메서드 → `@Rule TemporaryFolder`
- 시간 의존 메서드 → `Calendar.getInstance()` 로 기대값 생성 (JVM 기본 TZ 일치)
- 버그성 동작 → `@Test(expected = ...)` 로 회귀 방지하고 사유 주석에 명시
- 한국어 주석 허용
- Mockito 제네릭 stub 충돌 시 (`when(...).thenReturn(stub)` 컴파일 오류) → `doReturn(stub).when(...)` 로 우회

## 4. 설계 결정
다음 항목을 모두 명시 (해당 없으면 "해당 없음"):
- **Mock 전략**: 어떤 객체를 mock 했고 그 이유는 무엇인가
- **Mockito 매처 선택**: `eq` / `same` / `any` 사용 근거
- **검증 방식**: assert 결과값 vs verify 호출, `times()`/`never()` 사용 이유
- **환경 의존성 처리**: 타임존 / Locale / JVM 버전 / 파일 시스템
- **한계**: 통합 테스트가 필요한 영역, mock 으로 검증 못한 부분
- **사전 요건**: JDK 버전, vendor JAR, 환경변수 등

## 5. 실행 결과 리포트
- **명령**: `./build.sh test` 또는 `mvn test -Dtest={TestClass}`
- **결과 표**: Tests run / Failures / Errors / Skipped / 소요시간
- **BUILD 결과**: SUCCESS/FAILURE
- **stdout 노이즈**: production 코드의 의도하지 않은 출력 (예: `System.out.println` 잔재) 별도 표기

## 6. 보충 (반드시 포함)
- **테스트 미적용 영역과 사유**: 의도적 제외 항목 (out of scope, mock 한계, 환경 요구)
- **발견된 이슈 / 코드 스멜**: production 코드에서 발견한 잠재 결함 (수정은 별도 PR 권장)
- **재현 명령**: `mvn test -Dtest={TestClass}#{methodName}` 정확한 형태
- **회귀 방지 의도 명시**: `@Test(expected=...)` 등 버그 고정 케이스에 대한 사유
- **확장 가능 영역**: 통합 테스트 또는 추가 단위테스트로 다룰 수 있는 부분
- **기존 테스트와의 관계**: 중복/대체 여부, 통합 권장 사항

# 제약
- 새 의존성 추가 금지 (현 pom.xml 범위)
- production 코드 수정 금지 (테스트만 작성)
- 빌드 실패 시 즉시 진단/수정. 환경 문제(JDK/vendor JAR)면 사유 명시
- 기존 테스트와 동일 시나리오 반복 금지 — 보강이 필요하면 명시 후 통합 권장
- 한국어로 작성
````

---

## 사용 예시

````
# 역할 ... (위 프롬프트 본문)

# 입력
- 대상 클래스 (FQCN): net.infobank.iss.survey.dao.SurveyDaoImpl
- 대상 메서드 (시그니처): public List<SurveyDto> selectSurveyList(SurveyDto dto)
````

위와 같이 `{{TARGET_CLASS}}` / `{{TARGET_METHOD}}` 두 자리만 채워서 AI Agent 에 전달하면
6단계 보고서가 자동 생성된다.

---

## 적용 시 체크리스트

- [ ] 대상 소스의 분기 / 의존성 / 부수 효과를 모두 식별했는가
- [ ] 기존 `src/test/java` 내 동일 메서드 테스트 존재 여부 확인
- [ ] 6 섹션이 모두 포함되었는가 (요약 / 매트릭스 / 코드 / 설계 결정 / 실행 결과 / 보충)
- [ ] 분기 커버리지 매핑 표가 작성되었는가
- [ ] BUILD SUCCESS 가 확인되었는가 (실패 시 사유와 수정 내역 기록)
- [ ] production 코드 수정 없이 테스트만 추가했는가
- [ ] 한국어로 작성되었는가

---

## 참고 — 본 프로젝트의 단위테스트 관행 (요약)

### 1. DAO 테스트 (MyBatis SqlSessionDaoSupport 상속)
```java
@Before
public void setUp() {
    session = mock(SqlSessionTemplate.class);
    dao = new XxxDaoImpl();
    dao.setSqlSessionTemplate(session);
}
// 매퍼 ID + 파라미터 검증
verify(session).selectList("xxx.selectXxxList", dto);
verify(session, never()).selectList(eq("xxx.selectXxxLogList"), any());
```

### 2. Service 테스트 (`@Autowired` 패키지-프라이빗 필드)
```java
@Before
public void setUp() {
    service = new XxxServiceImpl();
    service.config = new Properties();   // 동일 패키지 → 직접 대입
}
```

### 3. IO 메서드 (Excel/파일 출력)
```java
@Rule public TemporaryFolder tempFolder = new TemporaryFolder();

File output = new File(tempFolder.getRoot(), "out.xlsx");
ExcelDownUtil.excelDown(template, title, data, output.getAbsolutePath());

try (XSSFWorkbook wb = new XSSFWorkbook(output)) {
    assertEquals("값", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
}
```

### 4. 시간 의존 메서드 (DateUtil 등)
```java
// JVM 기본 TZ 와 production 코드의 SimpleDateFormat 이 동일 기준
Calendar expected = Calendar.getInstance();
expected.clear();
expected.set(2024, Calendar.JANUARY, 15, 0, 0, 0);
assertEquals(expected.getTimeInMillis(), DateUtil.getTimeStamp("20240115", "yyyyMMdd").getTime());
```

### 5. 버그성 동작 회귀 방지
```java
/**
 * 현 구현은 ParseException 을 catch 만 하고 date null 인 채 date.getTime() 호출 → NPE.
 * 회귀 방지 차원에서 현 동작을 고정. 개선 시 본 테스트도 함께 수정 필요.
 */
@Test(expected = NullPointerException.class)
public void invalidString_throwsNpe_becauseParseExceptionIsSwallowed() {
    DateUtil.getTimeStamp("not-a-date", "yyyyMMdd");
}
```

### 6. Mockito 제네릭 stub 충돌 우회
```java
// when(session.selectList(...)).thenReturn(stub) 시
// 제네릭 추론 충돌(List<Object> vs List<SurveyDto>) 발생 가능
// → doReturn 으로 우회
doReturn(stub).when(session).selectList(eq(MAPPER_ID), same(dto));
```
