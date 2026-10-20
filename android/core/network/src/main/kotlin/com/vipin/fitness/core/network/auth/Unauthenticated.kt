package com.vipin.fitness.core.network.auth

/** Marks a Retrofit method that must be sent without a bearer token (login, register, refresh). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unauthenticated
