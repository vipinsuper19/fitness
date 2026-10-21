package com.vipin.fitness.core.database.di

import android.content.Context
import androidx.room.Room
import com.vipin.fitness.core.database.FitnessDatabase
import com.vipin.fitness.core.database.badges.SeenBadgeDao
import com.vipin.fitness.core.database.outbox.PendingWriteDao
import com.vipin.fitness.core.database.session.WorkoutSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): FitnessDatabase =
        Room.databaseBuilder(context, FitnessDatabase::class.java, FitnessDatabase.NAME).build()

    @Provides
    fun pendingWriteDao(db: FitnessDatabase): PendingWriteDao = db.pendingWriteDao()

    @Provides
    fun workoutSessionDao(db: FitnessDatabase): WorkoutSessionDao = db.workoutSessionDao()

    @Provides
    fun seenBadgeDao(db: FitnessDatabase): SeenBadgeDao = db.seenBadgeDao()
}
