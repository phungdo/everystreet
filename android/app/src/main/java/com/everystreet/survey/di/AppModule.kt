package com.everystreet.survey.di

import android.content.Context
import com.everystreet.survey.data.SurveyDatabase
import com.everystreet.survey.data.SurveyProgressDao
import com.everystreet.survey.data.SurveyRouteDao
import com.everystreet.survey.data.SurveyedStreetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SurveyDatabase {
        return SurveyDatabase.getDatabase(context)
    }

    @Provides
    fun provideSurveyRouteDao(database: SurveyDatabase): SurveyRouteDao {
        return database.surveyRouteDao()
    }

    @Provides
    fun provideSurveyedStreetDao(database: SurveyDatabase): SurveyedStreetDao {
        return database.surveyedStreetDao()
    }

    @Provides
    fun provideSurveyProgressDao(database: SurveyDatabase): SurveyProgressDao {
        return database.surveyProgressDao()
    }
}
