package com.sharenow.challenge

import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import org.threeten.bp.ZonedDateTime
import java.util.concurrent.TimeUnit

class AuthTokenProvider(

    /**
     * Scheduler used for background operations. Execute time relevant operations on this one,
     * so we can use [TestScheduler] within unit tests.
     */
    private val computationScheduler: Scheduler,

    /**
     * Single to be observed in order to get a new token.
     */
    private val refreshAuthToken: Single<AuthToken>,

    /**
     * Observable for the login state of the user. Will emit true, if he is logged in.
     */
    private val isLoggedInObservable: Observable<Boolean>,

    /**
     * Function that returns you the current time, whenever you need it. Please use this whenever you check the
     * current time, so we can manipulate time in unit tests.
     */
    private val currentTime: () -> ZonedDateTime
) {

    private var token: AuthToken? = null


    /**
     * @return the observable auth token as a string
     */
    fun observeToken(): Observable<String> {
        return Observable.merge(loggedInFilterObservable(), periodicTokenValidatorObservable())
            .flatMap {
                Observable.concat(
                    getFromCacheObservable(),
                    getFromNetworkObservable()
                ).firstElement().toObservable()
            }
            .filter { isValidToken(it) }
            .map { it.token }
    }

    /**
     * clear cache if not logged in or logged out
     * and emits only if logged in
     */
    private fun loggedInFilterObservable(): Observable<Boolean> {
        return isLoggedInObservable
            .doOnNext { loggedIn ->
                if (!loggedIn)
                    clearCachedToken()
            }
            .filter { it }
    }

    /**
     * it checks every 1 second an get the token from cache
     * and only emits if token is invalid or null
     * and clears the cache afterwords
     */
    private fun periodicTokenValidatorObservable(): Observable<Boolean> {
        return Observable.interval(0L, 1L, TimeUnit.SECONDS, computationScheduler)
            .map { isValidToken(token) }
            .filter { !it }     // emits if token is invalid
    }

    /**
     * it get the token from network and saves the token if valid
     * and retries once if the token is invalid
     */
    private fun getFromNetworkObservable(): Observable<AuthToken> {
        return refreshAuthToken
            .doOnSuccess {
                if (!isValidToken(it))
                    throw RuntimeException("invalid token") // to be caught with retry
                cacheToken(it)
            }.retry(1).toObservable()
    }


    /**
     * get the cached token if valid
     */
    private fun getFromCacheObservable(): Observable<AuthToken> {
        return Maybe.fromCallable<AuthToken> { token }
            .filter { isValidToken(it) }
            .toObservable()
    }

    private fun isValidToken(token: AuthToken?): Boolean = token?.isValid(currentTime) == true

    private fun cacheToken(token: AuthToken) {
        this.token = token
    }

    private fun clearCachedToken() {
        token = null
    }

}