# 분석 및 구현 설명 (analysis.md)

## 1. 데이터셋 개요

이 프로젝트는 과제에서 제공된 **COVID-19 Twitter 데이터셋(2020-03 ~ 2022-02)** 을 대상으로 한다.  
데이터는 여러 개의 CSV 파일로 나뉘어 있고, 파일 이름에 국가명이 포함되어 있다.

- 대상 국가: **Brazil, India, Japan, Indonesia, Australia**
- 공통적으로 등장하는 주요 컬럼
  - `created_at` : 트윗이 작성된 시각 (일부 국가에만 있음)
  - `text` 또는 `tweet` : 트윗 본문
  - `user_location` : 사용자 위치 (India에는 없음)
- India 전용 컬럼
  - `sentiment_score` : 미리 계산된 감성 점수
  - `month` : `"Mar 25"` 와 같은 날짜 라벨

이 프로젝트에서는 위 컬럼들을 하나의 도메인 객체 `Tweet` 으로 통합해서 다루도록 설계했다.

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

- `createdAt`, `userLocation`, `sentimentScore`, `monthRaw` 는 국가별로 존재 여부가 다르므로 **nullable** 로 선언했다.

---

## 2. 데이터 탐색 과정과 문제점

### 2.1. 단순 파싱 시도와 에러

처음에는 모든 CSV 파일을 `kotlin-csv` 라이브러리로 동일하게 파싱하려고 했다.  
그러나 `India.csv` 를 읽는 과정에서 다음과 같은 예외들이 반복적으로 발생했다.

- `CSVFieldNumDifferentException`: 특정 줄에서 필드 수가 헤더와 다름
- `CSVParseFormatException`: 따옴표(`"`)가 닫히지 않았다고 판단되는 포맷 오류

에러 메시지에 찍힌 row 번호를 기반으로 **직접 India.csv 일부를 열어보니**,  
트윗 본문 안에 줄바꿈, 콤마, 따옴표 등이 섞여 있어 일반적인 CSV 파서가 기대하는 형식과 달랐다.

### 2.2. 텍스트 특성

데이터를 직접 열어보면 트윗 본문에는 다음과 같은 요소들이 많이 포함되어 있다.

- URL (`https://...`)
- 해시태그 (`#covid19`, `#lockdown` 등)
- 멘션 (`@user`)
- 이모지, 특수문자
- HTML 엔티티 (`&amp;`, `&gt;` 등)

이들은 **분석 용도에 따라 적절히 제거/정규화**할 필요가 있었다.

### 2.3. 위치 정보 특성

`user_location` 컬럼은 다음과 같이 매우 자유로운 텍스트였다.

- `"New Delhi, India"`
- `"India"`
- `"Bangalore / Mumbai"`
- 이모지, 특수문자 섞인 문자열
- 완전히 비어 있거나 공백만 있는 경우

이 필드를 정제해두면 이후 분석 때 도움이 되지만,  
이번 과제에서는 **기본적인 공백 정리 수준**까지만 적용하기로 했다.

---

## 3. 전처리 및 설계 결정

### 3.1. 파일 로딩 전략

요구사항상, 평가자가 어떤 디렉터리에 데이터를 두든, **루트 디렉터리 경로 하나만 인자로 받아 전체 CSV를 처리**해야 한다.

```kotlin
fun loadAllTweets(root: Path): List<Tweet> =
    root.toFile()
        .walk()
        .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
        .filter { file ->
            file.parentFile?.name?.lowercase() != "output"
        }
        .flatMap { file ->
            val fileNameLower = file.name.lowercase()
            kotlin.runCatching {
                if ("india" in fileNameLower) {
                    loadIndiaTweets(file.toPath())
                } else {
                    loadTweetsFromFile(file.toPath())
                }
            }.onFailure { e ->
                println("Failed to read CSV: ${file.path} -> ${e.javaClass.simpleName}: ${e.message}")
            }.getOrElse { emptySequence() }
        }
        .toList()
```

- `walk()` 로 하위 디렉터리를 모두 순회
- `.csv` 확장자만 선택
- 분석 결과를 저장하는 `output/` 디렉터리는 **입력에서 제외**
- 각 파일을 읽을 때 `runCatching { ... }` 으로 감싸서,
  - 특정 파일에서 예외가 발생해도 전체 프로그램은 죽지 않고,
  - 실패한 파일 경로와 예외 타입을 로그로 남기도록 했다.

### 3.2. India 전용 수동 파서

India.csv는 일반적인 CSV 파서로 처리하기 어려웠기 때문에,  
**한 줄 단위로 수동 파싱하는 방식**으로 우회했다.

구조는 다음과 같이 가정했다.

- `id,tweet,sentiment_score,month`

문제는 `tweet` 안에 콤마가 마음대로 등장한다는 점이다.  
이를 해결하기 위해 다음 전략을 사용했다.

1. **왼쪽에서 첫 번째 콤마** → `id` 구분
2. **오른쪽에서 두 개의 콤마** → `sentiment`, `month` 구분
3. 나머지 가운데 부분 전체를 `tweet` 으로 간주

```kotlin
fun parseIndiaLine(rawLine: String): IndiaRow? {
    val line = rawLine.trimEnd('
', '')
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

    return IndiaRow(id = id, tweet = tweet, sentiment = sentiment, month = month)
}
```

- 형식이 이상해서 제대로 잘라낼 수 없는 줄은 `null` 을 반환하고, 상위 로직에서 `continue` 처리하여 **해당 줄만 건너뛴다**.
- 이렇게 하면 India.csv 전체가 망가진 것이 아니라 일부 줄만 이상한 상황에서,  
  **대부분의 데이터를 살리면서 에러 없이 분석을 진행**할 수 있다.

### 3.3. 날짜 처리와 월 단위 키

트위터의 `created_at` 문자열은 다음과 같은 포맷을 가진다.

- `"Wed Dec 08 04:25:46 +0000 2021"`

이를 Kotlin `LocalDateTime` 으로 변환하기 위해 `DateTimeFormatter` 를 직접 정의했다.

```kotlin
private val twitterFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)

fun parseTwitterDate(raw: String): LocalDateTime? =
    runCatching { ZonedDateTime.parse(raw, twitterFormatter).toLocalDateTime() }
        .getOrNull()
```

이후 월 단위 집계를 위해 `Tweet.monthKey()` 를 정의했다.

```kotlin
fun Tweet.monthKey(): String =
    createdAt
        ?.let { YearMonth.from(it).toString() }  // 예: "2021-12"
        ?: (monthRaw ?: "Unknown")
```

- `createdAt` 이 있는 국가: `"YYYY-MM"` 형식의 문자열 사용
- India처럼 `created_at` 이 없는 국가: 원본 `monthRaw` 문자열 사용
- 둘 다 없으면 `"Unknown"` 으로 처리

이를 기반으로

- 전 세계 기준 월별 트윗 수
- 국가 + 월별 트윗 수
- India 감성 점수의 날짜별 평균

을 계산했다.

### 3.4. 중복 제거 전략 (identityKey)

데이터셋 규모가 크고, 동일한 트윗이 중복되어 존재할 가능성이 있다.  
이를 제거하지 않으면 국가별/월별 집계가 실제보다 부풀려질 수 있다.

ID 컬럼이 모든 파일에 일관되게 존재하지 않기 때문에,  
다음 필드들을 묶어서 **사실상 ID 역할을 하는 surrogate key**를 만들었다.

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

그리고 전체 목록에 대해 `distinctBy` 를 적용했다.

```kotlin
val tweets: List<Tweet> = allTweets
    .distinctBy { it.identityKey() }
```

- 같은 내용의 트윗이 여러 번 있는 경우, **하나만 남기도록** 하는 역할
- India처럼 `createdAt` 이 없는 경우에는 `monthRaw + text` 조합으로 구분된다.

한계도 존재한다.

- 텍스트가 완전히 같고, 같은 시각/같은 monthRaw 인 서로 다른 트윗은 구분하지 못한다.
- 그러나 과제 수준에서는 오히려 중복을 어느 정도 공격적으로 제거하는 것이  
  집계 결과를 해석하기에 더 자연스럽다고 판단했다.

### 3.5. 텍스트 클리닝 (cleanText)

해시태그 집계 등 이후 처리를 위해, 텍스트를 어느 정도 정리할 필요가 있었다.

```kotlin
fun cleanText(raw: String): String {
    var text = raw

    // HTML 엔티티 간단 처리
    text = text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    // URL 제거
    text = text.replace(Regex("https?://\S+"), " ")

    // 이모지/제어문자 등 제거 (문자/숫자/공백/구두점만 남기기)
    text = text.replace(Regex("[^\p{L}\p{N}\p{P}\p{Z}]"), " ")

    // 다중 공백 정리
    text = Regex("\s+").replace(text, " ").trim()

    return text
}
```

- 이 함수는 주로 **해시태그 추출 전에** 적용된다.
- 이모지 자체를 분석 대상으로 삼지는 않았고,  
  대신 문장 구조를 단순화하는 데에 초점을 맞췄다.

### 3.6. 위치 정제 (normalizeLocation)

`user_location` 은 매우 자유로운 필드라, 과도한 전처리는 의미가 크지 않다고 판단했다.  
이번 과제에서는 다음 정도만 처리했다.

```kotlin
fun normalizeLocation(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val collapsed = Regex("\s+").replace(trimmed, " ")
    return collapsed
}
```

- 앞뒤 공백 제거
- 중복 공백을 하나로 축약
- 빈 문자열은 `null` 로 취급

향후 확장한다면 국가/도시명 인식, 좌표 변환 등을 고려할 수 있다.

### 3.7. 집계 로직 요약

최종적으로 프로그램은 다음과 같은 집계를 수행한다.

1. **국가별 트윗 수**

   ```kotlin
   val tweetsByCountry: Map<String, Int> =
       tweets.groupingBy { it.country }.eachCount()
   ```

2. **월별 전체 트윗 수**

   ```kotlin
   val tweetsByMonth: Map<String, Int> =
       tweets.groupingBy { it.monthKey() }.eachCount()
   ```

3. **국가 + 월별 트윗 수**

   ```kotlin
   val tweetsByCountryMonth: Map<String, Map<String, Int>> =
       tweets
           .groupBy { it.country }
           .mapValues { (_, list) ->
               list.groupingBy { it.monthKey() }.eachCount()
           }
   ```

4. **국가별 상위 해시태그 Top 5**

   ```kotlin
   val hashtagRegex = Regex("#\w+")
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

5. **India 감성 점수의 날짜별 평균**

   ```kotlin
   val monthLabelRegex = Regex("^[A-Za-z]{3} \d{2}$")
   val indiaSentimentByMonth: List<Triple<String, Double, Int>> =
       tweets
           .filter { it.country == "India" && it.sentimentScore != null }
           .groupBy { it.monthKey() }
           .map { (month, list) ->
               val scores = list.mapNotNull { it.sentimentScore }
               val avg = scores.average()
               Triple(month, avg, scores.size)
           }
           .filter { (month, _, _) ->
               monthLabelRegex.matches(month)
           }
           .sortedBy { it.first }
   ```

---

## 4. Kotlin 함수형 스타일 활용

이 과제의 의도 중 하나는 **Kotlin의 함수형/컬렉션 API를 활용한 데이터 전처리**이다.  
이번 구현에서 특히 많이 사용한 요소는 다음과 같다.

- `map`, `mapNotNull`, `flatMap`
- `filter`, `distinctBy`
- `groupBy`, `groupingBy { }.eachCount()`
- `Sequence` 와 `sequence { ... }` 빌더
- `runCatching { ... }.getOrElse { ... }`
- 파일 읽기에서 `use`, `useLines`

이러한 함수형 스타일을 사용하면

- 루프를 직접 돌리는 코드보다 **의도가 더 잘 드러나고**
- 데이터 흐름을 한 눈에 파악하기 쉬우며
- 예외/에러 처리도 로직과 분리해서 생각할 수 있다.

---

## 5. 출력 및 시각화 준비

분석 결과는 콘솔 출력뿐 아니라, **그래프를 그리기 쉬운 CSV 파일**로도 저장한다.

- `country_tweet_counts.csv`
  - `country,tweet_count`
  - 국가별 트윗 수 막대그래프용

- `month_tweet_counts.csv`
  - `month,tweet_count`
  - 전체 월별 트윗 수 라인그래프용

- `country_month_tweet_counts.csv`
  - `country,month,tweet_count`
  - 국가별 시간 추이(multi-line chart 또는 heatmap)용

- `hashtag_top5_by_country.csv`
  - `country,hashtag,count`
  - 국가별 상위 해시태그 비교용

- `india_sentiment_by_month.csv`
  - `month_label,average_sentiment,tweet_count`
  - India 감성 변화 시계열 그래프용

이렇게 CSV를 분리해 두면, 엑셀이나 파이썬(pandas, matplotlib)에서  
바로 그래프를 뽑을 수 있고, 과제 리포트 작성 시에도 재사용하기 좋다.

---

## 6. AI 도구(ChatGPT) 활용

이번 과제에서는 ChatGPT를 다음과 같은 방식으로 활용했다.

1. **과제 요구사항 정리**
   - PDF 과제 문서를 요약하고,  
     어떤 기능을 반드시 구현해야 하는지 체크리스트를 함께 정리했다.
2. **에러 분석 및 우회 전략 설계**
   - `CSVFieldNumDifferentException`, `CSVParseFormatException` 이 발생했을 때,
     - 어떤 상황에서 이런 에러가 나는지
     - India.csv 처럼 형식이 깨진 파일을 어떻게 처리할지
     에 대한 아이디어를 얻었다.
3. **India 전용 파서 설계**
   - `"왼쪽에서 1개, 오른쪽에서 2개 콤마를 기준으로 자른다"`는 전략을 함께 논의하며 설계했다.
4. **중복 제거(identityKey), 텍스트 정제(cleanText) 전략**
   - 어떤 필드들을 묶어서 surrogate key를 만들지,
   - URL/이모지/HTML 엔티티를 어떤 정도까지 제거할지에 대한 설계에 도움을 받았다.
5. **코드 리팩터링 및 README/analysis.md 구조**
   - 기능은 이미 동작하고 있었지만,
     - 출력 정리
     - CSV 출력 함수 분리
     - README와 analysis.md에서 무엇을 어떻게 설명할지
     를 함께 다듬었다.

최종 코드는 위 아이디어를 바탕으로 직접 수정·실행·디버깅하면서 완성했으며,  
로그 출력과 예외 메시지를 보면서 세부 동작을 검증했다.

---

## 7. 시행착오와 한계

### 7.1. India.csv 관련 시행착오

- 처음에는 다른 국가들과 동일하게 `kotlin-csv` 로 읽으려다,  
  특정 줄에서 필드 수가 맞지 않거나 따옴표가 닫히지 않았다는 에러가 발생했다.
- 특정 row 번호를 기준으로 직접 파일을 열어보니,  
  트윗 본문이 **여러 줄에 걸쳐 있거나**, 예상치 못한 위치에 따옴표가 등장하는 경우가 있었다.
- 해결책으로, India.csv는 **전용 수동 파서**를 만들고,
  문제가 있는 줄은 과감히 건너뛰는 방식으로 전체 분석을 진행했다.

### 7.2. output 폴더 재파싱 문제

- 초기에 `loadAllTweets` 가 단순히 `*.csv` 파일을 모두 읽도록 구현되어 있어서,
  한 번 실행 후 생성된 `output/*.csv` 역시 다시 입력으로 읽으려 했다.
- 이 결과, 프로그램을 두 번째 실행할 때 `output` 폴더의 CSV 형식이  
  원본 데이터와 다르기 때문에 파싱 에러가 발생했다.
- 이후 `parentFile.name != "output"` 조건을 추가하여  
  **출력 폴더는 입력 대상에서 제외**하도록 수정했다.

### 7.3. 텍스트 클리닝의 한계

- 현재 `cleanText` 는 URL, 이모지, 제어문자 등만 제거하고,  
  알파벳/숫자/구두점/공백만 남기도록 단순히 필터링한다.
- 여러 언어(예: 일본어, 인도 지역 언어 등)를 완벽하게 다루지는 못하며,  
  이 부분은 향후 개선 여지가 있다.
- 또한 감성 점수는 India 에만 제공되기 때문에,  
  다른 국가에 대해서는 단순 빈도 분석(트윗 수, 해시태그)까지만 수행했다.

---

## 8. 과제 요구사항과의 매핑

- **수백 개의 CSV 파일을 한 번에 처리**하는 구조
  - 루트 디렉터리 경로 하나를 인자로 받아, `walk()` 로 모든 CSV를 처리
- **데이터 품질 문제에 대한 대응**
  - India.csv 전용 수동 파서
  - 에러 발생 시 파일 단위로만 실패 처리
  - 중복 제거, 텍스트 클리닝, 위치 정제
- **국가별 비교**
  - 국가별 트윗 수, 국가별 상위 해시태그 Top 5
- **시간에 따른 변화**
  - 월별 전체 트윗 수
  - 국가 + 월별 트윗 수
  - India 감성 점수의 날짜별 변화
- **Kotlin 함수형 프로그래밍 활용**
  - `map`, `filter`, `flatMap`, `groupBy`, `groupingBy`, `Sequence`, `runCatching` 등 적극 활용
- **결과를 시각화 가능하게 제공**
  - 여러 개의 집계 결과를 CSV로 출력하여, 엑셀/파이썬에서 바로 그래프를 그릴 수 있도록 준비

이러한 점에서, 이 프로젝트는 과제 PDF에서 요구한  
“COVID-19 Twitter 데이터에 대한 전처리 및 탐색적 분석” 과제를  
충분히 충족하도록 설계·구현되었다고 판단한다.
