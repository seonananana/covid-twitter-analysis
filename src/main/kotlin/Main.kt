import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TOP_COUNTRY_COUNT = 5
private const val TOP_MONTH_COUNT = 10
private const val TOP_HASHTAG_COUNT = 5

// CSV 한 줄을 하나의 도메인 객체로 표현
data class Tweet(
    val country: String,
    val createdAt: LocalDateTime?,   // India 처럼 없을 수도 있어서 nullable
    val text: String,
    val userLocation: String?,       // India.csv에는 없으므로 nullable
    val sentimentScore: Double?,     // India.csv에만 있는 컬럼
    val monthRaw: String?            // India.csv의 month("Mar 25" 등)
)

// India 전용 파싱용 데이터 클래스
data class IndiaRow(
    val id: String,
    val tweet: String,
    val sentiment: String,
    val month: String
)

// phrase-count 파일용 데이터 클래스 (문장 + 빈도)
data class PhraseCount(
    val phrase: String,
    val count: Int
)

// Twitter created_at 포맷 예: "Wed Dec 08 04:25:46 +0000 2021"
private val twitterFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)

// 트윗 중복 제거용 키 (id가 없어서 country + createdAt + monthRaw + text 조합으로 surrogate key 생성)
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

// 위치 문자열 정제: 앞뒤 공백 제거 + 내부 공백 normalize
fun normalizeLocation(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val collapsed = Regex("\\s+").replace(trimmed, " ")
    return collapsed
}

// 텍스트 정제: URL/이모지/제어문자 제거, HTML 엔티티 일부 처리
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

fun main(args: Array<String>) {
    try {
        if (args.isEmpty()) {
            println("Usage: <program> <data directory path>")
            return
        }

        val dataDir = Paths.get(args[0])

        if (!Files.exists(dataDir)) {
            println("Data directory not found: $dataDir")
            return
        }

        // 1) 모든 CSV에서 트윗 읽어오기 (raw)
        val allTweets: List<Tweet> = loadAllTweets(dataDir)

        // 1-1) 중복 트윗 제거 (identityKey 기준으로 distinct)
        val tweets: List<Tweet> = allTweets
            .distinctBy { it.identityKey() }

        // 2) 미리 집계해두기
        val tweetsByCountry: Map<String, Int> =
            tweets.groupingBy { it.country }.eachCount()

        val tweetsByMonth: Map<String, Int> =
            tweets.groupingBy { it.monthKey() }.eachCount()

        // 나라별 + 월별 트윗 수
        val tweetsByCountryMonth: Map<String, Map<String, Int>> =
            tweets
                .groupBy { it.country }
                .mapValues { (_, list) ->
                    list.groupingBy { it.monthKey() }.eachCount()
                }

        // 해시태그 집계 (cleanText 거친 뒤 추출)
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

        // India 월별 감성 점수 평균 (+ 표본 개수)
        // month_label은 "Apr 01", "Mar 25" 같은 패턴만 사용
        val monthLabelRegex = Regex("^[A-Za-z]{3} \\d{2}$")
        val indiaSentimentByMonth: List<Triple<String, Double, Int>> =
            tweets
                .filter { it.country == "India" && it.sentimentScore != null }
                .groupBy { it.monthKey() }           // India는 monthRaw가 들어감
                .map { (month, list) ->
                    val scores = list.mapNotNull { it.sentimentScore }
                    val avg = scores.average()
                    Triple(month, avg, scores.size)  // (월 라벨, 평균, 개수)
                }
                .filter { (month, _, _) ->
                    monthLabelRegex.matches(month)
                }
                .sortedBy { it.first }               // 월 라벨순 정렬

        // 3) 그래프용 CSV 저장 디렉터리 생성
        val outDir = dataDir.resolve("output")
        Files.createDirectories(outDir)

        // 3-1) 나라별 트윗 수 CSV 저장
        writeCountryCountsCsv(
            path = outDir.resolve("country_tweet_counts.csv"),
            counts = tweetsByCountry
        )

        // 3-2) 월별 전체 트윗 수 CSV 저장
        writeMonthCountsCsv(
            path = outDir.resolve("month_tweet_counts.csv"),
            counts = tweetsByMonth
        )

        // 3-3) 나라별 해시태그 Top N CSV 저장
        writeHashtagTopCsv(
            path = outDir.resolve("hashtag_top${TOP_HASHTAG_COUNT}_by_country.csv"),
            hashtagsByCountry = hashtagsByCountry,
            topN = TOP_HASHTAG_COUNT
        )

        // 3-4) India 월별 감성 평균 CSV 저장
        writeIndiaSentimentByMonthCsv(
            path = outDir.resolve("india_sentiment_by_month.csv"),
            stats = indiaSentimentByMonth
        )

        // 3-5) 나라별·월별 트윗 수 CSV 저장
        writeCountryMonthCountsCsv(
            path = outDir.resolve("country_month_tweet_counts.csv"),
            counts = tweetsByCountryMonth
        )

        // 4) 요약 출력 시작

        println("=== Summary ===")
        println("Total raw tweets: ${allTweets.size}")
        println("Total unique tweets: ${tweets.size}")

        // 국가별 상위 N개
        println("\n=== Top $TOP_COUNTRY_COUNT countries by tweet volume ===")
        tweetsByCountry
            .entries
            .sortedByDescending { it.value }
            .take(TOP_COUNTRY_COUNT)
            .forEach { (country, count) ->
                println("$country\t$count")
            }

        // 월별 상위 N개 (트윗 수가 많은 순)
        println("\n=== Top $TOP_MONTH_COUNT months by tweet volume ===")
        tweetsByMonth
            .entries
            .sortedByDescending { it.value }
            .take(TOP_MONTH_COUNT)
            .forEach { (month, count) ->
                println("$month\t$count")
            }

        // 국가별 해시태그 Top N개 (기본 5개)
        println("\n=== Top $TOP_HASHTAG_COUNT hashtags by country ===")
        hashtagsByCountry
            .toSortedMap() // country 이름 기준 정렬
            .forEach { (country, counts) ->
                println("[$country]")
                counts.entries
                    .sortedByDescending { it.value }
                    .take(TOP_HASHTAG_COUNT)
                    .forEach { (tag, count) ->
                        println("  $tag\t$count")
                    }
            }

        // 감성 점수 평균 (sentiment_score가 있는 나라만)
        println("\n=== Average sentiment (only rows with sentiment_score) ===")
        tweets
            .filter { it.sentimentScore != null }
            .groupBy { it.country }
            .forEach { (country, list) ->
                val avg = list.mapNotNull { it.sentimentScore }.average()
                println("$country\t$avg")
            }

        // India 월별 감성 평균 출력
        println("\n=== India average sentiment by month (filtered labels) ===")
        if (indiaSentimentByMonth.isEmpty()) {
            println("(no sentiment data for India)")
        } else {
            indiaSentimentByMonth.forEach { (month, avg, count) ->
                println("$month\t$avg\t(count=$count)")
            }
        }

        // phrase-count 파일(있으면) 분석
        val phraseFile = dataDir.resolve("india_phrases.txt")
        val phraseCounts = loadPhraseCounts(phraseFile)

        if (phraseCounts.isNotEmpty()) {
            println("\n=== Top 20 phrases from india_phrases.txt ===")
            phraseCounts
                .sortedByDescending { it.count }
                .take(20)
                .forEach { pc ->
                    println("${pc.count}\t${pc.phrase}")
                }

            println("\n=== Top 20 phrases containing 'India' ===")
            phraseCounts
                .filter { "india" in it.phrase.lowercase() }
                .sortedByDescending { it.count }
                .take(20)
                .forEach { pc ->
                    println("${pc.count}\t${pc.phrase}")
                }
        }
    } catch (e: Exception) {
        println("Exception during program execution:")
        e.printStackTrace()
    }
}

// createdAt이 있으면 YearMonth("2020-03"), 없고 monthRaw만 있으면 그 문자열 사용
fun Tweet.monthKey(): String =
    createdAt
        ?.let { YearMonth.from(it).toString() }
        ?: (monthRaw ?: "Unknown")

// 루트 디렉토리 아래 모든 .csv 파일을 찾아서 전부 로드
//  - India.csv 는 수동 파서 사용
//  - 나머지는 kotlin-csv 사용
//  - data/output/*.csv (집계 결과)는 입력에서 제외
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
                // println("Reading: ${file.path}")
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

// India.csv 전용: 직접 문자열 파싱해서 깨진 줄은 건너뛰기
fun loadIndiaTweets(path: Path): Sequence<Tweet> = sequence {
    path.toFile().bufferedReader().use { reader ->
        // 헤더 한 줄 스킵 (id,tweet,sentiment_score,month)
        reader.readLine()

        while (true) {
            val rawLine = reader.readLine() ?: break
            val row = parseIndiaLine(rawLine) ?: continue

            val sentiment = row.sentiment.trim().toDoubleOrNull()
            val monthRaw = row.month.trim()
            val text = row.tweet

            yield(
                Tweet(
                    country = "India",
                    createdAt = null,          // India.csv에는 created_at 컬럼이 없음
                    text = text,
                    userLocation = null,
                    sentimentScore = sentiment,
                    monthRaw = monthRaw
                )
            )
        }
    }
}

// India 한 줄 파싱: id,tweet,sentiment,month 구조를
// "왼쪽에서 1개, 오른쪽에서 2개" 콤마 기준으로 자르기
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

    return IndiaRow(
        id = id,
        tweet = tweet,
        sentiment = sentiment,
        month = month
    )
}

// (India 이외) 단일 CSV 파일 -> 여러 Tweet
fun loadTweetsFromFile(path: Path): Sequence<Tweet> {
    val fileName = path.fileName.toString()
    val country = guessCountryFromFileName(fileName)

    val reader = csvReader {
        delimiter = ','
        quoteChar = '"'
        skipEmptyLine = true
        // 나머지 옵션은 기본값 (엄격 모드)
    }

    return reader.readAllWithHeader(path.toFile())
        .asSequence()
        .map { row ->
            val createdAtStr = row["created_at"]
            val createdAt: LocalDateTime? = createdAtStr
                ?.takeIf { it.isNotBlank() }
                ?.let { parseTwitterDate(it) }

            val text: String = row["text"]
                ?: row["tweet"]
                ?: ""

            val userLocation: String? = normalizeLocation(row["user_location"])
            val sentimentScore: Double? = row["sentiment_score"]?.toDoubleOrNull()
            val monthRaw: String? = row["month"]

            Tweet(
                country = country,
                createdAt = createdAt,
                text = text,
                userLocation = userLocation,
                sentimentScore = sentimentScore,
                monthRaw = monthRaw
            )
        }
}

// Twitter created_at 포맷 파싱 (실패하면 null)
fun parseTwitterDate(raw: String): LocalDateTime? =
    runCatching { ZonedDateTime.parse(raw, twitterFormatter).toLocalDateTime() }
        .getOrNull()

// 파일 이름에서 국가 이름 추출
fun guessCountryFromFileName(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
        "australia" in lower -> "Australia"
        "brazil" in lower -> "Brazil"
        "india" in lower -> "India"
        "indonesia" in lower -> "Indonesia"
        "japan" in lower -> "Japan"
        else -> "Unknown"
    }
}

// "문장<TAB>숫자" 형식 파일 로딩
fun loadPhraseCounts(path: Path): List<PhraseCount> {
    if (!Files.exists(path)) return emptyList()

    return path.toFile().useLines { lines ->
        lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val idx = line.lastIndexOf('\t')
                if (idx == -1) return@mapNotNull null

                val phrase = line.substring(0, idx).trim()
                val countStr = line.substring(idx + 1).trim()
                val count = countStr.toIntOrNull() ?: return@mapNotNull null

                PhraseCount(phrase = phrase, count = count)
            }
            .toList()
    }
}

// 나라별 트윗 수 CSV 저장: country,tweet_count
fun writeCountryCountsCsv(path: Path, counts: Map<String, Int>) {
    path.toFile().bufferedWriter().use { w ->
        w.appendLine("country,tweet_count")
        counts.entries
            .sortedByDescending { it.value }
            .forEach { (country, count) ->
                w.appendLine("$country,$count")
            }
    }
}

// 월별 트윗 수 CSV 저장: month,tweet_count
fun writeMonthCountsCsv(path: Path, counts: Map<String, Int>) {
    path.toFile().bufferedWriter().use { w ->
        w.appendLine("month,tweet_count")
        counts.entries
            .sortedByDescending { it.value }
            .forEach { (month, count) ->
                w.appendLine("$month,$count")
            }
    }
}

// 나라별 해시태그 Top N 저장: country,hashtag,count
fun writeHashtagTopCsv(
    path: Path,
    hashtagsByCountry: Map<String, Map<String, Int>>,
    topN: Int
) {
    path.toFile().bufferedWriter().use { w ->
        w.appendLine("country,hashtag,count")
        hashtagsByCountry.forEach { (country, counts) ->
            counts.entries
                .sortedByDescending { it.value }
                .take(topN)
                .forEach { (tag, count) ->
                    // 해시태그나 country에 콤마 생길 일은 거의 없지만, 안전하게 따옴표로 감싸줌
                    val safeCountry = "\"$country\""
                    val safeTag = "\"$tag\""
                    w.appendLine("$safeCountry,$safeTag,$count")
                }
        }
    }
}

// India 월별 감성 평균 CSV 저장: month_label,average_sentiment,tweet_count
fun writeIndiaSentimentByMonthCsv(
    path: Path,
    stats: List<Triple<String, Double, Int>>
) {
    path.toFile().bufferedWriter().use { w ->
        w.appendLine("month_label,average_sentiment,tweet_count")
        stats.forEach { (month, avg, count) ->
            val safeMonth = "\"$month\""
            w.appendLine("$safeMonth,$avg,$count")
        }
    }
}

// 나라별·월별 트윗 수 CSV 저장: country,month,tweet_count
fun writeCountryMonthCountsCsv(
    path: Path,
    counts: Map<String, Map<String, Int>>
) {
    path.toFile().bufferedWriter().use { w ->
        w.appendLine("country,month,tweet_count")
        counts.forEach { (country, monthMap) ->
            monthMap.entries
                .sortedBy { it.key }
                .forEach { (month, count) ->
                    val safeCountry = "\"$country\""
                    val safeMonth = "\"$month\""
                    w.appendLine("$safeCountry,$safeMonth,$count")
                }
        }
    }
}
