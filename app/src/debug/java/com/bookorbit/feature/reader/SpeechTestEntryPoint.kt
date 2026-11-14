package com.bookorbit.feature.reader

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The fixture and foreground service must observe the same speech session. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SpeechTestEntryPoint {
    fun speech(): ReaderSpeechPlayer
}
