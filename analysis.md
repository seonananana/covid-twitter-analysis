# covid-twitter-analysis

Kotlin + Gradle로 구현한 **COVID-19 트위터 데이터 분석 과제 (Assignment 2)** 프로젝트입니다.  
2020년 3월 ~ 2022년 2월 사이, 5개 국가(Brazil, India, Japan, Indonesia, Australia)의 트윗을 한 번에 읽어서:

- 국가별 트윗 수
- 월별 트윗 수
- 국가별 상위 해시태그
- (India 전용) 감성 점수 평균 및 시간 변화

등을 집계하고, 그래프용 CSV 파일로 내보내는 프로그램입니다.

---

## 1. 프로젝트 개요

이 프로젝트의 목표는 다음과 같습니다.

- 과제에서 제공한 **수많은 CSV 파일**을 한 번에 읽어들여,
- **국가별 / 월별 / 해시태그 / 감성 점수** 단위로 COVID-19 관련 트윗을 분석하고,
- 이후 **엑셀/파이썬에서 바로 그래프를 그릴 수 있는 형태의 CSV 결과물**을 만드는 것

이를 위해 Kotlin 컬렉션/함수형 API(`map`, `filter`, `groupBy`, `groupingBy`, `Sequence`, `runCatching` 등)를 활용하여
데이터 전처리와 집계를 수행합니다.

---

## 2. 데이터 파일 위치 및 준비 방법

### 2.1. 필요 데이터

과제에서 제공한 COVID-19 Twitter 데이터셋을 사용합니다.

- 대상 국가: `Australia`, `Brazil`, `India`, `Indonesia`, `Japan`
- 기간: 2020년 3월 ~ 2022년 2월
- 형식: 여러 개의 `.csv` 파일

각 CSV 파일에는 대략 다음과 같은 컬럼들이 포함되어 있습니다.

- 공통(또는 일부 국가에서만 존재)
  - `created_at` : 트윗 작성 시각
  - `text` 또는 `tweet` : 트윗 본문
  - `user_location` : 사용자 위치
- India 전용
  - `sentiment_score` : 감성 점수
  - `month` : `"Mar 25"` 와 같은 날짜 라벨

### 2.2. 데이터 폴더 구성

프로젝트 루트에 `data/` 폴더를 만들고, 그 안에 CSV 파일들을 넣습니다.

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

또는, 여러 개의 날짜/국가별 CSV가 섞여 있는 폴더를 그대로 사용해도 됩니다.  
프로그램은 **루트 디렉터리 아래의 모든 `.csv` 파일을 자동으로 탐색**합니다.

> ⚠ 단, 분석 결과를 기록하는 `data/output/` 폴더는 입력에서 제외되도록 되어 있습니다.  
> (`output` 폴더에 생성된 CSV는 다시 읽지 않음)

---

## 3. 프로젝트 실행 방법

### 3.1. 환경 요구 사항

- JDK 17 (또는 과제에서 지정한 JDK 버전)
- Gradle 래퍼(`gradlew`) 사용 가능
- IntelliJ IDEA (또는 Kotlin/Gradle을 지원하는 IDE, 선택사항)

### 3.2. Gradle로 실행 (권장)

프로젝트 루트에서 다음 명령을 실행합니다.

```bash
# Windows (PowerShell or CMD)
gradlew run --args="data"

# macOS / Linux / WSL
./gradlew run --args="data"
```

- `data` 는 **데이터 CSV 파일들이 들어있는 디렉터리 경로**입니다.
- 데이터가 다른 경로에 있을 경우:

```bash
./gradlew run --args="/absolute/path/to/your/data"
```

경로가 잘못되면 프로그램은:

```text
Data directory not found: <경로>
```

메시지를 출력하고 종료합니다.

### 3.3. IntelliJ IDEA에서 실행

1. IntelliJ에서 `File → Open` 으로 `covid-twitter-analysis` 폴더를 엽니다.
2. Gradle 프로젝트로 인식되면 자동으로 설정이 로드됩니다.
3. `Run → Edit Configurations...` 에서:
   - **Main class**: `MainKt`
   - **Program arguments**: `data` (또는 데이터 폴더 절대경로)
4. Run 버튼으로 실행합니다.

---

## 4. 주요 실행 결과 (예시)

제공된 과제 데이터셋을 기준으로, 프로그램을 실행했을 때의 **대표적인 집계 결과**는 다음과 같습니다.  
(실제 숫자는 데이터셋 버전에 따라 조금 달라질 수 있습니다.)

### 4.1. 전체 트윗 수

- 전체 트윗 수 (raw, 중복 포함): **약 1,615,653건**
- 중복 제거 후 유니크 트윗 수: 약 160만 건  
  (중복 제거 기준: `country + createdAt + monthRaw + text` 조합)

### 4.2. 국가별 트윗 수 (Top 5)

예시 실행 결과:

- **Brazil** : 422,041
- **Japan** : 414,948
- **Australia** : 410,197
- **Indonesia** : 229,935
- **India** : 138,501

→ Brazil, Japan, Australia에서 COVID-19 관련 트윗이 특히 많이 수집되었음을 확인할 수 있습니다.

### 4.3. 월별 트윗 수 (Top 10)

전 세계(5개국 전체)를 기준으로 한 월별 트윗 수 Top 10 예시는 다음과 같습니다.

1. 2021-12 : 598,697건  
2. 2021-02 : 158,729건  
3. 2021-03 : 152,423건  
4. 2021-08 : 148,492건  
5. 2022-01 : 92,700건  
6. 2021-09 : 83,596건  
7. 2021-11 : 67,025건  
8. 2021-04 : 55,233건  
9. 2021-05 : 35,627건  
10. 2021-10 : 30,657건  

→ 2021년 12월, 2021년 초(2~3월), 2021년 8월에 트윗량이 크게 치솟는 패턴을 볼 수 있습니다.

### 4.4. 국가별 상위 해시태그 (Top 5, 예시)

각 국가별로 상위 5개 해시태그를 추출한 결과, 공통적으로:

- `#covid19`
- `#covid`
- `#coronavirus`

와 같은 태그가 상위권에 있으며, 국가별로는 다음과 같이 차이가 나타납니다.

- **Australia** : `#auspol`, `#omicron` 등
- **Brazil** : `#srilanka`, `#covid19sl` 등
- **India** : `#corona`, `#lockdown` 등
- **Indonesia** : `#indonesia`, `#jakpost` 등
- **Japan** : `#taiwan`, `#vaccine` 등

이를 통해 국가별 논의 주제와 관심사가 어떻게 다른지 파악할 수 있습니다.

### 4.5. India 감성 점수 (평균 및 시간 변화)

`India.csv`에는 `sentiment_score` 컬럼이 존재하므로, 인도 트윗에 대해 감성 분석 결과를 활용할 수 있습니다.

- India 평균 감성 점수 (전체): **약 0.379**

또한 날짜 라벨(`"Mar 25"`, `"Apr 01"` 등)별로 평균 감성 점수와 표본 개수를 계산하여:

- 날짜별 감성 변화 라인 그래프
- 특정 시기(락다운 발표, 확진자 급증 시점 등)에 감성이 어떻게 변하는지 비교

와 같은 시각화를 수행할 수 있습니다.

---

## 5. 프로그램 내부 동작 및 결과 파일

### 5.1. 내부 처리 요약

프로그램은 크게 다음 단계를 거칩니다.

1. **CSV 로딩**
   - 루트 디렉터리 아래의 모든 `.csv` 파일을 재귀적으로 탐색하여 읽습니다.
   - `data/output/` 폴더는 입력에서 제외됩니다.
   - Australia/Brazil/Indonesia/Japan CSV는 `kotlin-csv` 라이브러리로 파싱합니다.
   - India.csv는 여러 줄에 걸친 트윗, 깨진 따옴표 등으로 인해
     일반 파서가 실패하여, **직접 문자열을 잘라서 파싱하는 수동 파서**를 사용합니다.

2. **전처리**
   - 모든 데이터를 `Tweet` 도메인 객체로 통일:

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

   - `identityKey`(country + createdAt + monthRaw + text)를 이용해 **중복 트윗 제거**
   - `cleanText` 함수로 텍스트 정제:
     - URL 제거
     - HTML 엔티티(`&amp;`, `&lt;`, `&gt;`) 일부 변환
     - 이모지/제어문자 제거
   - `normalizeLocation` 함수로 위치 문자열 정리:
     - 앞뒤 공백 제거
     - 중복 공백을 한 칸으로 축소
   - `monthKey()`를 통해 `"YYYY-MM"` 또는 원본 `monthRaw` 기반의 월 키 생성

3. **집계**
   - 국가별 트윗 수
   - 월별 전체 트윗 수
   - 국가 + 월별 트윗 수
   - 국가별 상위 해시태그 Top 5
   - India 감성 점수의 날짜별 평균(라벨 + 평균 + 표본 개수)

### 5.2. 생성되는 결과 CSV 파일

프로그램 실행 후, `data/output/` 폴더에 다음 CSV 파일들이 생성됩니다.

1. `country_tweet_counts.csv`

   | column       | 설명                   |
   |-------------|------------------------|
   | country     | 국가 이름              |
   | tweet_count | 해당 국가의 트윗 수    |

2. `month_tweet_counts.csv`

   | column       | 설명                         |
   |-------------|------------------------------|
   | month       | `"YYYY-MM"` 형식 월 라벨     |
   | tweet_count | 해당 월의 전체 트윗 수       |

3. `country_month_tweet_counts.csv`

   | column       | 설명                                  |
   |-------------|---------------------------------------|
   | country     | 국가 이름                             |
   | month       | 월 라벨 (`"YYYY-MM"` 또는 India 원본) |
   | tweet_count | 해당 국가+월의 트윗 수                |

4. `hashtag_top5_by_country.csv`

   | column   | 설명                        |
   |----------|-----------------------------|
   | country  | 국가 이름                   |
   | hashtag  | 해시태그 (예: `#covid19`)   |
   | count    | 해당 해시태그 등장 횟수     |

5. `india_sentiment_by_month.csv`

   | column           | 설명                                     |
   |------------------|------------------------------------------|
   | month_label      | `"Mar 25"`, `"Apr 01"` 등 날짜 라벨      |
   | average_sentiment| 해당 날짜의 평균 감성 점수               |
   | tweet_count      | 해당 날짜에 사용된 표본 트윗 개수        |

이 CSV 파일들을 엑셀이나 파이썬(pandas, matplotlib 등)으로 불러와서  
막대그래프, 라인그래프, heatmap 등의 형태로 쉽게 시각화할 수 있습니다.

---

## 6. AI 도구(ChatGPT) 활용 방법

이 프로젝트를 진행하면서 **AI 도구(ChatGPT)** 를 다음과 같이 활용했습니다.

1. **과제 조건 해석 및 체크리스트 작성**
   - PDF 과제 문서를 요약하고,
   - “프로젝트 실행 방법, 데이터 파일 위치/준비, 주요 실행 결과, AI 도구 활용 방법” 등
     README에 꼭 들어가야 할 항목들을 정리했습니다.

2. **에러 분석 및 설계 보조**
   - `CSVFieldNumDifferentException`, `CSVParseFormatException` 등 CSV 파싱 에러가 발생했을 때,
     - 어떤 줄에서 포맷이 깨졌는지,
     - India.csv처럼 형식이 불완전한 파일을 어떻게 우회할지
     에 대한 아이디어를 얻었습니다.

3. **India 전용 파서 설계**
   - `id,tweet,sentiment_score,month` 구조에서,
     - 왼쪽에서 첫 번째 콤마 → `id`
     - 오른쪽에서 두 개의 콤마 → `sentiment`, `month`
     - 나머지 가운데 전체 → `tweet`
     와 같이 자르는 전략을 함께 논의했습니다.

4. **전처리/집계 로직 구조화**
   - 중복 제거용 `identityKey` 설계,
   - 텍스트 정제 함수(`cleanText`)의 규칙(HTML 엔티티, URL, 이모지 처리 등),
   - Kotlin 컬렉션/함수형 API 사용 패턴(`map`, `filter`, `groupBy`, `groupingBy`, `Sequence`, `runCatching`)을
     깔끔하게 정리하는 데 도움을 받았습니다.

5. **문서화(README.md, analysis.md) 구성**
   - README에 어떤 섹션을 넣어야 과제 요구사항을 충족하는지,
   - analysis.md에서 어떤 내용을 설명해야 “데이터 구조 이해 + 문제 해결 과정 + 설계 근거”를 잘 보여줄 수 있는지
     구조를 잡을 때 AI 도구를 참고했습니다.

최종 코드는 이러한 아이디어를 바탕으로 직접 수정·실행·디버깅하면서 완성했습니다.

---

## 7. 저장소 구성 및 제출 메모

- GitHub 저장소에는 **코드와 문서만** 포함되어 있습니다.
  - 포함:
    - `src/`
    - `build.gradle.kts`
    - `settings.gradle.kts`
    - `gradle/`, `gradlew`, `gradlew.bat`
    - `README.md`
    - `analysis.md`
    - `.gitignore`
  - 제외:
    - `data/` 폴더 및 모든 `.csv` 데이터 파일
- `data/`와 `*.csv` 는 `.gitignore`에 등록하여,  
  과제 데이터셋이 외부에 업로드되지 않도록 했습니다.

---

## 8. 라이선스

이 저장소는 수업 과제 제출을 위한 코드로, 별도의 오픈소스 라이선스를 명시하지 않았습니다.  
필요 시 수업/과제 범위 내에서만 사용합니다.
