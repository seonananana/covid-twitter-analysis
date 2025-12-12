# Covid Twitter Analysis – 분석 보고서

## 1. 데이터 개요와 문제 인식

- 사용 데이터  
  - `Australia.csv`, `Brazil.csv`, `India.csv`, `Indonesia.csv`, `Japan.csv`  
  - 각 파일은 코로나 관련 트윗을 국가별로 모은 데이터셋이다.
- 공통 컬럼(국가별로 조금씩 다름)
  - `created_at`: 트윗 작성 시간 (트위터 포맷 문자열)
  - `text` 또는 `tweet`: 트윗 내용
  - `user_location`: 사용자가 적은 위치 정보
  - `sentiment_score`: 감성 점수 (India 전용)
  - `month`: India 전용 월·일 문자열(예: `Mar 25`)
- 첫 인상 / 문제점
  - 인코딩이 제각각이라 한글·이모지 등이 깨지는 구간이 있음.
  - `India.csv`는 일반 CSV 파서로는 에러가 나는 줄이 많음
    - 필드 수가 중간에 바뀜
    - 따옴표(`"`)가 짝이 안 맞는 행이 있음
  - 국가별로 컬럼 구성이 달라서 공통 도메인 모델로 묶기가 애매함
  - 중복 트윗, URL·이모지·HTML 엔티티 등 “노이즈”가 많음

> 이번 과제의 목표  
> 1) **전처리 파이프라인 설계**  
> 2) **함수형 Kotlin 스타일로 집계 로직 구현**  
> 3) **국가/월/해시태그/감성 점수 관점에서 트윗 패턴 탐색**

---

## 2. 전처리 설계

### 2.1 공통 도메인 모델

모든 CSV를 아래 데이터 클래스로 수렴시켰다.

```kotlin
data class Tweet(
    val country: String,
    val createdAt: LocalDateTime?,   // India에는 없음 → nullable
    val text: String,
    val userLocation: String?,       // India에는 없음 → nullable
    val sentimentScore: Double?,     // India 전용 컬럼
    val monthRaw: String?            // India 전용 month("Mar 25" 등)
)
```

나라마다 컬럼이 달라서 있는 컬럼만 채우고 나머지는 `null`로 두는 전략을 사용했다.  
이후 집계 로직에서는 `createdAt` 또는 `monthRaw` 중 하나만 있어도 동작하도록 설계했다.

---

### 2.2 Twitter 날짜 파싱

트위터의 `created_at` 포맷은 예를 들어 다음과 같다.

- `Wed Dec 08 04:25:46 +0000 2021`

이를 위해 `DateTimeFormatter`를 직접 정의했다.

```kotlin
private val twitterFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)

fun parseTwitterDate(raw: String): LocalDateTime? =
    runCatching { ZonedDateTime.parse(raw, twitterFormatter).toLocalDateTime() }
        .getOrNull()
```

포맷이 안 맞는 줄은 `runCatching`으로 감싸 예외를 삼키고 `null`로 처리했다.  
이렇게 하면 특정 줄 하나가 깨져도 전체 파이프라인은 멈추지 않는다.

---

### 2.3 India.csv 전용 커스텀 파서

`India.csv`는 일반 CSV 파서를 쓰면 아래와 같은 에러가 났다.

- `CSVFieldNumDifferentException`
- `CSVParseFormatException: must appear escapeChar(") after escapeChar(")`

원인은:

- 트윗 안에 따옴표, 콤마, 줄바꿈이 마구 섞여 있고
- 일부 줄은 따옴표 짝이 맞지 않아서 “정상적인 CSV”가 아니었기 때문이다.

그래서 India 전용으로 직접 한 줄씩 파싱하는 커스텀 파서를 만들었다.

```kotlin
data class IndiaRow(
    val id: String,
    val tweet: String,
    val sentiment: String,
    val month: String
)

// "왼쪽에서 1개, 오른쪽에서 2개" 콤마를 기준으로 나누기
fun parseIndiaLine(rawLine: String): IndiaRow? {
    val line = rawLine.trimEnd('\n', '\r')
    if (line.isBlank()) return null

    val firstComma = line.indexOf(',')
    if (firstComma == -1) return null

    val lastComma = line.lastIndexOf(',')
    if (lastComma <= firstComma) return null

    val secondLastComma = line.lastIndexOf(',', lastComma - 1)
    if (secondLastComma <= firstComma) return null

    val id = line.substring(0, firstComma)
    val tweet = line.substring(firstComma + 1, secondLastComma)
    val sentiment = line.substring(secondLastComma + 1, lastComma)
    val month = line.substring(lastComma + 1)

    return IndiaRow(id, tweet, sentiment, month)
}
```

그리고 이를 `Tweet`으로 변환한다.

```kotlin
fun loadIndiaTweets(path: Path): Sequence<Tweet> = sequence {
    path.toFile().bufferedReader().use { reader ->
        reader.readLine() // 헤더 스킵

        while (true) {
            val rawLine = reader.readLine() ?: break
            val row = parseIndiaLine(rawLine) ?: continue

            val sentiment = row.sentiment.trim().toDoubleOrNull()
            val monthRaw = row.month.trim()
            val text = row.tweet

            yield(
                Tweet(
                    country = "India",
                    createdAt = null,
                    text = text,
                    userLocation = null,
                    sentimentScore = sentiment,
                    monthRaw = monthRaw
                )
            )
        }
    }
}
```

이렇게 하면 India 데이터는 깨진 줄은 건너뛰면서 대부분의 레코드를 살려서 사용할 수 있다.

---

### 2.4 중복 제거 전략(identityKey)

트윗 id 컬럼이 없기 때문에, 아래와 같이 대체 키(surrogate key)를 만들어 중복을 제거했다.

```kotlin
fun Tweet.identityKey(): String =
    buildString {
        append(country)
        append('|')
        append(createdAt?.toString() ?: "")
        append('|')
        append(monthRaw ?: "")
        append('|')
        append(text)
    }
```

```kotlin
val tweets: List<Tweet> =
    loadAllTweets(dataDir)
        .distinctBy { it.identityKey() }
```

완벽하진 않지만, 같은 시각(또는 같은 `monthRaw`)에 내용이 완전히 동일한 트윗은 같은 것으로 보고 제거했다.  
리트윗이 많을수록 중복 감소 효과가 크다.

---

### 2.5 위치 문자열 정제(normalizeLocation)

`user_location`은 자유 텍스트라서 공백이 뒤죽박죽이다.

```kotlin
fun normalizeLocation(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val collapsed = Regex("\\s+").replace(trimmed, " ")
    return collapsed
}
```

- 앞뒤 공백 제거
- 중간 공백 여러 개 → 한 칸으로 축소
- 완전히 빈 문자열은 `null` 처리

향후에는 여기에서 `Delhi, India → Delhi / India` 같은 도시/국가 표준화 로직을 더 붙일 수 있다.

---

### 2.6 텍스트 정제(cleanText)

과제 확장 포인트였던 텍스트 클리닝을 실제 코드로 구현했다.

```kotlin
fun cleanText(raw: String): String {
    var text = raw

    // HTML 엔티티 간단 처리
    text = text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    // URL 제거
    text = text.replace(Regex("https?://\\S+"), " ")

    // 이모지/제어문자 등 제거 (문자/숫자/공백/구두점만 남기기)
    text = text.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), " ")

    // 다중 공백 정리
    text = Regex("\\s+").replace(text, " ").trim()

    return text
}
```

해시태그 집계 시에는 `cleanText(tweet.text)` 결과에서 `#\\w+`를 찾도록 변경했다.  
그 결과, URL·이모지에 묻힌 해시태그가 조금 더 안정적으로 추출된다.

---

## 3. Kotlin 함수형 스타일 활용

이번 과제에서 의도적으로 함수형 Kotlin 스타일을 많이 사용했다.

### 3.1 시퀀스(Sequence)와 스트림 처리

파일 탐색:

```kotlin
fun loadAllTweets(root: Path): List<Tweet> =
    root.toFile()
        .walk()
        .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
        .filter { file -> file.parentFile?.name?.lowercase() != "output" } // 결과 CSV 재파싱 방지
        .flatMap { file ->
            val name = file.name.lowercase()
            runCatching {
                if ("india" in name) loadIndiaTweets(file.toPath())
                else loadTweetsFromFile(file.toPath())
            }.onFailure { e ->
                println("Failed to read CSV: ${file.path} -> ${e.javaClass.simpleName}: ${e.message}")
            }.getOrElse { emptySequence() }
        }
        .toList()
```

India 전용 파서는 `sequence { ... }`를 사용해서 지연 평가(lazy) + 예외 견고성을 모두 확보했다.

---

### 3.2 groupingBy / map / flatMap

국가별 트윗 수:

```kotlin
val tweetsByCountry: Map<String, Int> =
    tweets.groupingBy { it.country }.eachCount()
```

월별 트윗 수:

```kotlin
fun Tweet.monthKey(): String =
    createdAt?.let { YearMonth.from(it).toString() } ?: (monthRaw ?: "Unknown")

val tweetsByMonth: Map<String, Int> =
    tweets.groupingBy { it.monthKey() }.eachCount()
```

국가별 해시태그 집계 (텍스트 정제 포함):

```kotlin
val hashtagRegex = Regex("#\\w+")

val hashtagsByCountry: Map<String, Map<String, Int>> =
    tweets
        .flatMap { tweet ->
            val base = cleanText(tweet.text).lowercase()
            val tags = hashtagRegex.findAll(base)
                .map { it.value }
                .toList()
            tags.map { tag -> tweet.country to tag }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, tags) ->
            tags.groupingBy { it }.eachCount()
        }
```

---

### 3.3 안전한 예외 처리(runCatching)

CSV 한 줄이 잘못되어도 전체 프로그램이 죽지 않도록, 대부분의 I/O, 파싱 로직을 `runCatching { ... }.getOrElse { ... }` 패턴으로 감쌌다.  
특히 India 파서에서 유효하지 않은 줄은 `parseIndiaLine(...) ?: continue`로 조용히 건너뛰도록 구현했다.

---

## 4. 집계 결과 요약

전체적으로 약 160만 건의 트윗을 분석했다.  
(중복 제거 후에는 소폭 줄어든다.)

### 4.1 국가별 트윗 수 (상위 5개)

실행 로그 기준 상위 5개 국가는 다음과 같다.

- Brazil: 397,595  
- Japan: 366,434  
- Australia: 409,849  
- Indonesia: 192,057  
- India: 137,169  

→ 오스트레일리아·브라질·일본이 비슷한 규모로 가장 많은 트윗을 생성했고, 인도는 트윗 수 자체는 적지만 감성 점수가 있어 질적 분석이 가능하다.

---

### 4.2 월별 전체 트윗 수 (상위 10개)

- 2021-12: 531,875  
- 2021-02: 154,529  
- 2021-03: 151,745  
- 2021-08: 129,325  
- 2022-01: 92,555  
- 2021-09: 83,552  
- 2021-11: 66,963  
- 2021-04: 51,413  
- 2021-05: 33,058  
- 2021-10: 30,021  

→ 2021년 12월에 압도적으로 트윗이 폭증했고, 2021년 2~3월, 8월에도 작은 피크가 보인다.  
(델타/오미크론 변이 확산 시점과 맞물린 패턴으로 추정 가능)

---

### 4.3 국가별 상위 해시태그 Top 5

대표적인 결과는 다음과 같다.

- 공통적으로 `#covid19`, `#covid`, `#coronavirus`가 상위권
- Australia: `#auspol`, `#omicron`, `#breaking` → 정치·뉴스 중심
- Brazil: `#srilanka`, `#covid19sl` 등 다른 나라 상황도 함께 언급
- India: `#corona`, `#lockdown` → 봉쇄 정책 관련 이슈 강조
- Indonesia: `#indonesia`, `#stayhome`
- Japan: `#taiwan`, `#vaccine` → 백신·국제 관계 이슈가 많이 언급

---

### 4.4 India 감성 점수

전체적으로 India의 평균 감성 점수는:

- 평균 약 0.35 (0~1 스케일이라고 가정하면 중립에 가까운 약한 긍정)

일자별로 보면:

- 봉쇄/확진 증가 뉴스가 많은 날에는 평균이 떨어지고
- 축제·회복·백신 관련 긍정 뉴스가 많은 날에는 평균이 올라가는 패턴을 확인했다

---

## 5. 시각화 결과 (엑셀로 그린 그래프)

`data/output`에 생성된 CSV를 엑셀로 읽어서 다음과 같은 그래프를 만들었다.  
(레포지토리에서는 `docs/`나 `figs/` 폴더에 PNG로 저장해두면 된다.)

### 5.1 국가별 트윗 수 막대그래프

- x축: 국가 (Brazil, Japan, Australia, Indonesia, India)
- y축: `tweet_count`

해석:
- Australia, Brazil, Japan 세 나라가 거의 비슷한 규모로 가장 많은 트윗을 생성
- Indonesia는 중간 정도
- India는 트윗 수는 가장 적지만 감성 점수·텍스트 내용이 풍부해서 질적 분석에 유리

---

### 5.2 국가별 상위 해시태그 Top 5 막대그래프

- x축: (국가, 해시태그) 조합
- y축: `count`

한 눈에 보이는 것:
- 모든 국가에서 `#covid19`가 1등
- Australia는 정치 태그 `#auspol` 비중이 높고
- Japan은 `#vaccine`, `#taiwan`이 두드러진다
- Indonesia는 `#stayhome` 비중이 높아 행동 지침 캠페인이 활발했음을 시사

---

### 5.3 인도: 일자별 트윗 수 vs 평균 감성

데이터: `india_sentiment_by_month.csv`

- `month_label`(예: `Mar 25`, `Aug 01`)
- `average_sentiment`
- `tweet_count`

엑셀에서 동일 축에 두 시리즈를 그려 “한 날의 트윗 양과 감성”을 같이 확인했다.

대략적인 패턴:
- 확진자 급증 / 봉쇄 관련 뉴스가 많았던 날에는  
  `tweet_count`는 높고 `average_sentiment`는 낮은 편
- 축제·회복·백신 관련 긍정 뉴스가 많은 날에는  
  트윗 수는 비슷하더라도 `average_sentiment`가 올라가는 구간이 존재

(엑셀 차트에서는 레이블이 많이 섞여 다소 복잡하지만, 주요 피크를 확대해서 보는 식으로 분석하면 좋다.)

---

### 5.4 월별 전체 트윗 수 막대그래프

- x축: `2021-12`, `2021-02`, ...
- y축: `tweet_count`

해석:
- 2021-12 월이 압도적인 피크
- 2021-02, 2021-03, 2021-08도 눈에 띄는 봉우리
- 2020년(맨 오른쪽의 작은 막대들)은 상대적으로 트윗 수가 적다

이를 코로나 실제 확진자/정책 타임라인과 겹쳐 보면 어떤 나라에서 어떤 시점에 이슈가 커졌는지 추론할 수 있다.

---

## 6. 확장 기능 및 고급 집계

과제 핵심인 “전처리 + 함수형 Kotlin”을 조금 더 확장해서 다음 기능도 구현했다.

### 6.1 나라별·월별 트윗 수 매트릭스

```kotlin
val tweetsByCountryMonth: Map<String, Map<String, Int>> =
    tweets
        .groupBy { it.country }
        .mapValues { (_, list) ->
            list.groupingBy { it.monthKey() }.eachCount()
        }

writeCountryMonthCountsCsv(
    path = outDir.resolve("country_month_tweet_counts.csv"),
    counts = tweetsByCountryMonth
)
```

결과 CSV 형식: `country,month,tweet_count`

- 이 파일을 피벗 테이블로 놓으면 각 국가의 시간별 트렌드를 한 눈에 비교 가능
- 예: “일본은 2021-12에 급증, 인도는 2020-3~5에 집중” 식 분석 가능

---

### 6.2 키워드 트렌드 샘플 (예: "vaccine")

메인 함수 마지막에서 키워드별 빈도를 샘플로 확인했다.

```kotlin
val keyword = "vaccine"
val keywordCountsByCountry =
    tweets
        .filter { cleanText(it.text).contains(keyword, ignoreCase = true) }
        .groupingBy { it.country }
        .eachCount()

println("\n=== Keyword trend sample: '$keyword' total count by country ===")
keywordCountsByCountry
    .entries
    .sortedByDescending { it.value }
    .forEach { (country, count) ->
        println("$country\t$count")
    }
```

실행 결과 예:
- Australia: 28,879
- Brazil: 40,521
- India: 1,299
- Indonesia: 14,690
- Japan: 43,204

→ 백신 관련 키워드는 일본과 브라질에서 특히 많이 등장한다는 사실을 확인할 수 있다.

---

### 6.3 phrase-count 파일 분석 (선택 기능)

추가 텍스트 파일 `india_phrases.txt`가 있을 경우,  
"문장<TAB>빈도" 형식으로 읽어서 상위 20개 문장을 출력하는 기능도 구현했다.

```kotlin
data class PhraseCount(val phrase: String, val count: Int)

fun loadPhraseCounts(path: Path): List<PhraseCount> { /* ... */ }
```

상위 문장만 모아서 보면 사람들이 반복해서 언급하는 대표적인 서술 패턴을 확인할 수 있다.  
`"you can’t get a container..."`처럼 밈(meme)에 가까운 문장들도 눈에 띈다.

---

## 7. AI 도구와의 협업 과정

이번 과제는 단순 구현을 넘어, AI 도구(ChatGPT)를 다음과 같이 활용했다.

- 초기 설계 단계
  - CSV 구조를 설명하고
  - “India.csv가 깨져 있어서 일반 파서가 안 되는데 어떻게 처리하면 좋을까?”를 질문
  - 답변을 기반으로 `parseIndiaLine` 같은 커스텀 파서 아이디어를 얻음
- 코드 개선
  - Sequence, groupingBy, flatMap, runCatching 등을 활용하는 함수형 스타일로 리팩터링 방향을 반복적으로 질의·응답
  - 예외 처리·리소스 관리를 `use {}`와 `runCatching`으로 정리
- 전처리 확장
  - 공백 정리 수준에서 시작한 텍스트 클리닝을 URL 제거, HTML 엔티티 처리, 이모지 제거, 중복 제거까지 확장
  - `identityKey` 전략, 위치 정규화 로직 등을 함께 설계
- 분석·보고서 작성
  - 실행 로그와 엑셀 그래프 캡처를 공유하고
  - 그에 맞는 해석 문장, 보고서 구조(섹션 구성)를 다듬음

최종적으로,

- 전처리: 커스텀 파서 + 텍스트/위치 클리닝 + 중복 제거  
- 함수형 Kotlin: Sequence, groupingBy, flatMap, runCatching 적극 활용  
- 집계/시각화: 국가·월·해시태그·감성·키워드 트렌드를 다양한 시각에서 분석  

으로, 과제 PDF에서 요구한 내용을 충분히 넘는 수준의 파이프라인을 구현했다.
