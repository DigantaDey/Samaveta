package com.samaveta.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * A single injectable clock.
 *
 * Time is injected rather than read from `System.currentTimeMillis()` inside logic so that decay,
 * freshness ranking and confirmation expiry are all deterministic under test. Hilt cannot provide
 * a bare function type, so it is provided under a named binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeModule {

    @Provides
    @Singleton
    @Named("clock")
    fun provideClock(): () -> Long = { System.currentTimeMillis() }
}
