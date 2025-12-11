plugins {
    kotlin("jvm") version "1.9.24"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    // CSV 파싱 라이브러리
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")
}

application {
    // src/main/kotlin/Main.kt 기준
    mainClass.set("MainKt")
}
