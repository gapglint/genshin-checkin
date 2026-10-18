package com.lesumein.hoyocheckin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 호요랩 서버 시각. 폰 시계는 틀릴 수 있어서, 호요랩 응답의 Date 헤더로 폰 시계와의 차이를 기억해 두고
 * 출석 날짜와 다음 출석 시각을 서버 기준으로 계산한다.
 * 출석의 하루는 UTC+8 자정(한국 시간 새벽 1시)에 바뀐다.
 */
object ServerClock {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 서버 시각 - 폰 시각(ms). 아직 모르면 0. */
    @Volatile
    var offsetMs: Long = 0

    /** 응답의 Date 헤더(초 단위)를 받으면 차이를 갱신한다. 네트워크 지연 정도의 오차는 무시한다. */
    fun observe(serverMs: Long, phoneMs: Long = System.currentTimeMillis()) {
        if (serverMs > 0) offsetMs = serverMs - phoneMs
    }

    fun now(): Long = System.currentTimeMillis() + offsetMs

    /** 서버 기준 오늘(출석 날짜). */
    fun today(): LocalDate = dayOf(now())

    fun dayOf(serverMs: Long): LocalDate = Instant.ofEpochMilli(serverMs).atZone(ZONE).toLocalDate()

    /** serverMs 다음에 오는 서버 자정(ms). */
    fun nextReset(serverMs: Long): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(serverMs), ZONE)
            .toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
}
