# covid-twitter-analysis

Kotlin + Gradle로 구현한 **COVID-19 트위터 데이터 분석 과제 (Assignment 2)** 프로젝트입니다.  
2020년 3월 ~ 2022년 2월 사이, 5개 국가(Brazil, India, Japan, Indonesia, Australia)의 트윗을 한 번에 읽어서:

- 국가별 트윗 수
- 월별 트윗 수
- 국가별 상위 해시태그
- (India 전용) 감성 점수 평균 및 시간 변화

등을 집계하고, 그래프용 CSV 파일로 내보내는 프로그램입니다.

---

## 1. 프로젝트 구조

```text
covid-twitter-analysis/
  ├── src/
  │   └── main/
  │       └── kotlin/
  │           └── Main.kt         # 메인 분석 코드
  ├── build.gradle.kts            # Gradle 설정
  ├── README.md                   # 이 파일
  ├── analysis.md                 # 분석/구현 설명 (별도 작성)
  └── data/                       # (제출 시에는 비워두거나 .gitignore 처리)
      ├── Australia.csv
      ├── Brazil.csv
      ├── India.csv
      ├── Indonesia.csv
      ├── Japan.csv
      └── output/                 # 프로그램 실행 후 생성되는 결과 CSV들
```

> ⚠ **제출용 Git 저장소에는 `data/` 폴더와 CSV 파일을 포함하지 않습니다.**  
> (로컬에서만 데이터 파일을 두고 테스트합니다.)

---

## 2. 사전 준비

### 2.1. 필요 환경

- JDK 17 (또는 과제에서 지정한 JDK 버전)
- Gradle 래퍼(`gradlew`) 사용 가능
- (선택) IntelliJ IDEA / 기타 Kotlin 지원 IDE

### 2.2. 데이터 준비

1. 과제에서 제공한 COVID-19 Twitter 데이터셋을 다운로드합니다.  
   (예: `Australia.csv`, `Brazil.csv`, `India.csv`, `Indonesia.csv`, `Japan.csv` 5개 파일 등)

2. 다음 두 가지 방식 중 하나로 사용합니다.

#### A. 프로젝트 내부 `data/` 폴더 사용 (편한 방법)

- 프로젝트 루트에 `data/` 폴더를 만들고, 그 안에 CSV 파일들을 넣습니다.

  ```text
  covid-twitter-analysis/
    ├── data/
    │   ├── Australia.csv
    │   ├── Brazil.csv
    │   ├── India.csv
    │   ├── Indonesia.csv
    │   └── Japan.csv
    └── ...
  ```

- 실행 시에는 `data` 폴더를 인자로 넘깁니다.

  ```bash
  ./gradlew run --args="data"
  ```

#### B. 임의의 경로 사용

- 데이터가 다른 위치에 있는 경우, 해당 경로를 그대로 인자로 넘깁니다.

  ```bash
  ./gradlew run --args="C:\path	o\covid-data"
  ./gradlew run --args="/home/user/covid-data"
  ```

> 프로그램은 **폴더 내부의 모든 `.csv` 파일을 자동으로 탐색**하여 읽습니다.  
> 단, 분석 결과를 저장하는 `output/` 폴더는 자동으로 제외됩니다.

---

## 3. 실행 방법

### 3.1. Gradle로 실행

프로젝트 루트에서 아래 명령을 실행합니다.

```bash
# Windows
gradlew run --args="data"

# macOS / Linux
./gradlew run --args="data"
```

- `data` 부분은 **데이터가 들어 있는 디렉터리 경로**입니다.
- 올바른 경로가 아니면 `Data directory not found: ...` 메시지를 출력하고 종료합니다.

### 3.2. IntelliJ에서 실행

1. `File → Open` 으로 프로젝트 폴더(`covid-twitter-analysis`)를 엽니다.
2. Gradle 프로젝트로 인식되면 자동으로 설정이 로딩됩니다.
3. `Run → Edit Configurations...` 에서
    - Main class: `MainKt`
    - Program arguments: `data` (또는 데이터 폴더 절대경로)
4. Run 버튼으로 실행합니다.

---

## 4. 프로그램이 하는 일 (요약)

### 4.1. 입력 데이터 처리

- 루트 폴더 아래의 모든 `.csv` 파일을 순회하며 트윗을 읽습니다.
- `data/output/*.csv` 처럼 **프로그램이 생성한 결과 파일은 입력에서 제외**합니다.
- 파일 이름에 `australia`, `brazil`, `india`, `indonesia`, `japan` 이 들어가는지 확인하여 **국가를 추론**합니다.
- `Australia/Brazil/Indonesia/Japan` 파일들은 `kotlin-csv` 라이브러리로 읽습니다.
- `India.csv`는 포맷이 불완전해서, **직접 문자열을 파싱하는 수동 파서**를 사용합니다.
- 각 줄은 `Tweet` 도메인 객체로 변환됩니다.

```kotlin
data class Tweet(
    val country: String,
    val createdAt: LocalDateTime?,
    val text: String,
    val userLocation: String?,
    val sentimentScore: Double?,
    val monthRaw: String?
)
```

### 4.2. 데이터 전처리

- **중복 트윗 제거**
    - `country + createdAt + monthRaw + text` 조합으로 surrogate key를 만들어  
      `distinctBy`로 중복 트윗을 제거합니다.
- **텍스트 정제**
    - HTML 엔티티(`&amp;`, `&lt;`, `&gt;`) 일부 변환
    - URL 제거 (`https?://...`)
    - 이모지 및 제어문자 제거
    - 공백 정리 및 소문자 변환
- **위치 정보 정제**
    - `user_location` 컬럼에서 앞뒤 공백을 제거하고  
      중복 공백을 하나로 줄인 문자열만 사용합니다. (빈 문자열은 `null` 처리)

### 4.3. 집계 내용

프로그램은 다음과 같은 집계를 수행합니다.

1. **국가별 트윗 수**

    - `tweets.groupingBy { it.country }.eachCount()`

2. **월별 전체 트윗 수**

    - `Tweet.monthKey()`를 기준으로 그룹:
        - `createdAt`이 있는 경우: `YearMonth.from(createdAt)` → `"2021-12"`
        - India처럼 `created_at`이 없는 경우: `monthRaw` 사용 (`"Mar 25"` 등)
    - 전 세계(5개 국가 전체)를 기준으로 월별 트윗 수를 집계

3. **국가 + 월별 트윗 수**

    - `(country, monthKey)` 쌍으로 그룹핑
    - 국가별 트렌드를 시간축으로 비교할 수 있도록 집계

4. **국가별 상위 해시태그 Top 5**

    - 정제된 텍스트에서 `#\w+` 패턴으로 해시태그를 추출
    - 각 국가별로 자주 등장한 해시태그 상위 5개를 계산

5. **India 감성 점수 분석**

    - `India.csv`에만 있는 `sentiment_score` 컬럼을 사용
    - India 트윗 중 `sentiment_score != null` 인 것만 사용
    - `monthRaw`(예: `"Mar 25"`, `"Apr 01"`)를 기준으로 그룹
    - `"Apr 01"`, `"Mar 25"` 형식(`^[A-Za-z]{3} \d{2}$`)인 라벨만 사용하여
        - 각 날짜별 평균 감성 점수
        - 각 날짜별 표본 개수
    - 시간에 따른 감성 변화 라인 차트를 그릴 수 있는 형태로 정리

---

## 5. 출력 형식

### 5.1. 콘솔 출력

프로그램 실행 시, 콘솔에는 대략 다음 내용이 출력됩니다.

- 총 트윗 수 (raw / 중복 제거 후)
- 국가별 상위 N개 트윗 수 (`TOP_COUNTRY_COUNT = 5`)
- 월별 상위 N개 트윗 수 (`TOP_MONTH_COUNT = 10`)
- 국가별 상위 해시태그 Top 5
- 국가별 평균 감성 점수 (현재는 India만 대상)
- India 날짜별 평균 감성 점수 (필터링된 라벨 기준)
- 선택: `india_phrases.txt` 가 있을 경우, 상위 문구 분석

### 5.2. 결과 CSV 파일

실행 후 `data/output/` 폴더에 다음 CSV 파일들이 생성됩니다.

1. `country_tweet_counts.csv`

   | column       | 설명                   |
      |-------------|------------------------|
   | country     | 국가 이름              |
   | tweet_count | 해당 국가의 트윗 수    |

   → 국가별 트윗 수 막대 그래프에 사용.

2. `month_tweet_counts.csv`

   | column       | 설명                         |
      |-------------|------------------------------|
   | month       | `"YYYY-MM"` 형식 월 라벨     |
   | tweet_count | 해당 월의 전체 트윗 수       |

   → 전체 월별 트윗 수 추이 라인 그래프에 사용.

3. `country_month_tweet_counts.csv`

   | column       | 설명                                  |
      |-------------|---------------------------------------|
   | country     | 국가 이름                             |
   | month       | 월 라벨 (`"YYYY-MM"` 또는 India 원본) |
   | tweet_count | 해당 국가+월의 트윗 수                |

   → 국가별 트렌드(라인 그래프) 또는 heatmap 등에 사용.

4. `hashtag_top5_by_country.csv`

   | column   | 설명                        |
      |----------|-----------------------------|
   | country  | 국가 이름                   |
   | hashtag  | 해시태그 (예: `#covid19`)   |
   | count    | 해당 해시태그 등장 횟수     |

   → 국가별로 어떤 이슈/프레임이 강조되는지 시각화할 때 사용.

5. `india_sentiment_by_month.csv`

   | column           | 설명                                     |
      |------------------|------------------------------------------|
   | month_label      | `"Mar 25"`, `"Apr 01"` 등 날짜 라벨      |
   | average_sentiment| 해당 날짜의 평균 감성 점수               |
   | tweet_count      | 해당 날짜에 사용된 표본 트윗 개수        |

   → India 감성 변화 시계열 그래프에 사용.

---

## 6. 분석 및 그래프 활용 예시

생성된 CSV 파일들은 엑셀 또는 파이썬(pandas, matplotlib 등)으로 쉽게 시각화할 수 있습니다.

예를 들어:

- `country_tweet_counts.csv`  
  → 국가별 트윗 수 막대 그래프

- `month_tweet_counts.csv`  
  → 전체 트윗 수의 시간 추이 라인 그래프

- `country_month_tweet_counts.csv`  
  → 국가별 시간 추이 (multi-line chart 또는 heatmap)

- `india_sentiment_by_month.csv`  
  → India 감성 점수의 시간 추이 라인 그래프

이 그래프들을 보고서나 발표 자료에 그대로 사용할 수 있습니다.

---

## 7. AI 도구 활용 (선택 사항)

이 프로젝트를 진행하면서 **ChatGPT**를 다음과 같이 활용했습니다.

- Kotlin/Gradle 프로젝트 구조를 잡을 때, `main(args)`로 인자를 받는 패턴을 확인
- `kotlin-csv` 라이브러리 사용법 및 파싱 에러(`CSVFieldNumDifferentException`, `CSVParseFormatException`) 해결
- `India.csv`처럼 형식이 깨진 CSV를 위한 **수동 파서(parseIndiaLine)** 설계
- 중복 제거 전략(`identityKey`)과 텍스트 정제 함수(`cleanText`) 설계에 대한 아이디어 참고
- 과제 PDF에 나와 있는 요구사항을 하나씩 대조하며, 어떤 집계를 코드로 구현할지 정리
- 결과를 CSV로 내보내고, 추후 엑셀/파이썬으로 그래프를 그리는 워크플로 설계

최종 설계와 구현은 위 요구사항에 맞추어 직접 수정·보완하면서 마무리했습니다.

---

## 8. 라이선스

이 저장소는 과제 제출을 위한 코드로, 별도의 오픈소스 라이선스를 명시하지 않았습니다.  
필요 시 수업/과제 범위 내에서만 사용합니다.
