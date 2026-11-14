package com.bookorbit.feature.reader

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class ReaderSpeechViewModel @Inject constructor(val player: ReaderSpeechPlayer) :
    ViewModel()
