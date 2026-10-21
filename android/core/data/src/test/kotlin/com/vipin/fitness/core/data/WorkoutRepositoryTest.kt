package com.vipin.fitness.core.data

import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.DefaultWorkoutRepository
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.ExerciseFilter
import com.vipin.fitness.core.data.workout.GoalType
import com.vipin.fitness.core.data.workout.MuscleGroup
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanGenerateRequest
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.WorkoutApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek

class WorkoutRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repo: DefaultWorkoutRepository

    @Before fun setUp() {
        server.start()
        val json = fitnessJson()
        repo = DefaultWorkoutRepository(retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(WorkoutApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    private val exerciseJson = """{"id":"e1","slug":"push-up","name":"Push-up","nameHi":"पुश-अप","primaryMuscle":"chest",
        "secondaryMuscles":["triceps","unknown_muscle"],"equipment":["none"],"trackingType":"reps","difficulty":"beginner",
        "instructions":["Lower","Push"],"videoUrl":null}"""

    private fun planJson(active: Boolean = true) = """{"id":"p1","version":1,"name":"PPL","weeks":4,"rationale":"3-day split",
        "workouts":[{"id":"w2","dayOfWeek":3,"name":"Pull","exercises":[{"exerciseId":"e2","order":1,"sets":3},{"exerciseId":"e1","order":0,"sets":4,"repsMin":8,"repsMax":12,"restSeconds":60}]},
                    {"id":"w1","dayOfWeek":1,"name":"Push","exercises":[{"exerciseId":"e1","order":0,"sets":3}]}],
        "isActive":$active,"source":"rule_based","createdAt":"2026-10-21T08:00:00Z"}"""

    @Test fun noActivePlanIs204() = runTest {
        server.enqueue(MockResponse(code = 204))
        assertEquals(ApiResult.Success(ActivePlan.None), repo.refreshActivePlan())
        assertEquals(ActivePlan.None, repo.activePlan.value)
    }

    @Test fun activePlanSortedByDayAndOrder() = runTest {
        server.enqueue(MockResponse(code = 200, body = planJson()))
        val plan = ((repo.refreshActivePlan() as ApiResult.Success).value as ActivePlan.Some).plan
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), plan.workouts.map { it.day })
        assertEquals(listOf("e1", "e2"), plan.workoutOn(DayOfWeek.WEDNESDAY)!!.exercises.map { it.exerciseId })
        assertEquals(PlanSource.RULE_BASED, plan.source)
        assertNull(plan.workoutOn(DayOfWeek.SUNDAY))
    }

    @Test fun activePlanServerErrorIsFailureAndKeepsState() = runTest {
        server.enqueue(MockResponse(code = 500, body = """{"code":"INTERNAL","message":"x","requestId":"r"}"""))
        val r = repo.refreshActivePlan() as ApiResult.Failure
        assertEquals("INTERNAL", (r.error as ApiError.Http).code)
        assertNull(repo.activePlan.value)
    }

    @Test fun exerciseSearchSendsFiltersAndCachesForLookup() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[$exerciseJson],"nextCursor":"c2"}"""))
        val page = (repo.exercises(ExerciseFilter(" push ", MuscleGroup.CHEST, Equipment.NONE), cursor = "c1") as ApiResult.Success).value
        val url = server.takeRequest().url
        assertEquals("push", url.queryParameter("query"))
        assertEquals("chest", url.queryParameter("muscle"))
        assertEquals("none", url.queryParameter("equipment"))
        assertEquals("c1", url.queryParameter("cursor"))
        assertEquals("c2", page.nextCursor)
        val e = page.items.single()
        assertEquals(listOf(MuscleGroup.TRICEPS), e.secondaryMuscles) // unknown dropped
        assertEquals(TrackingType.REPS, e.trackingType)
        assertEquals(ExperienceLevel.BEGINNER, e.difficulty)
        // Served from cache: no second request.
        assertEquals("Push-up", (repo.exercise("e1") as ApiResult.Success).value.name)
        assertEquals(1, server.requestCount)
    }

    @Test fun emptyFilterOmitsQueryParams() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[],"nextCursor":null}"""))
        repo.exercises(ExerciseFilter())
        val url = server.takeRequest().url
        assertNull(url.queryParameter("query"))
        assertNull(url.queryParameter("muscle"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test fun generateSendsContractBody() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"name":"Starter","weeks":4,"generator":"rule_based","rationale":"why",
                    "workouts":[{"dayOfWeek":2,"name":"Full body","exercises":[{"exerciseId":"e1","order":0,"sets":3,"repsMin":8,"repsMax":12,"restSeconds":90}]}]}""",
            ),
        )
        val draft = (
            repo.generate(
                PlanGenerateRequest(GoalType.GAIN_MUSCLE, ExperienceLevel.BEGINNER, 3, 45, setOf(Equipment.DUMBBELL, Equipment.NONE)),
            ) as ApiResult.Success
            ).value
        assertEquals(
            """{"goal":"gain_muscle","experienceLevel":"beginner","daysPerWeek":3,"sessionMinutes":45,"equipment":["dumbbell"]}""",
            server.takeRequest().body?.utf8(),
        )
        assertEquals(DayOfWeek.TUESDAY, draft.workouts.single().day)
    }

    @Test fun createSendsDraftWithOrderAndKeyThenActivateUpdatesState() = runTest {
        server.enqueue(MockResponse(code = 201, body = planJson(active = false)))
        server.enqueue(MockResponse(code = 200, body = planJson(active = true)))
        val draft = PlanDraft(
            name = "Mine", weeks = 2, source = PlanSource.CUSTOM, rationale = null,
            workouts = listOf(
                PlannedWorkout(DayOfWeek.FRIDAY, "Legs", listOf(PlannedExercise("e3", 3, 10, 12))),
                PlannedWorkout(DayOfWeek.MONDAY, "Upper", listOf(PlannedExercise("e1", 3), PlannedExercise("e2", 2, durationSeconds = 60))),
            ),
        )
        val created = (repo.create(draft, "k1") as ApiResult.Success).value
        val req = server.takeRequest()
        assertEquals("k1", req.headers["Idempotency-Key"])
        assertEquals(
            """{"name":"Mine","weeks":2,"generator":"custom","workouts":[""" +
                """{"dayOfWeek":1,"name":"Upper","exercises":[{"exerciseId":"e1","order":0,"sets":3},{"exerciseId":"e2","order":1,"sets":2,"durationSeconds":60}]},""" +
                """{"dayOfWeek":5,"name":"Legs","exercises":[{"exerciseId":"e3","order":0,"sets":3,"repsMin":10,"repsMax":12}]}]}""",
            req.body?.utf8(),
        )
        repo.activate(created.id, "k2")
        val act = server.takeRequest()
        assertEquals("/api/v1/workout-plans/p1/activate", act.url.encodedPath)
        assertEquals("k2", act.headers["Idempotency-Key"])
        assertTrue((repo.activePlan.value as ActivePlan.Some).plan.isActive)
    }
}
