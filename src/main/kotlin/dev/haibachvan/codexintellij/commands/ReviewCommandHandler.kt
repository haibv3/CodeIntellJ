package dev.haibachvan.codexintellij.commands

import dev.haibachvan.codexintellij.review.ReviewController
import dev.haibachvan.codexintellij.review.ReviewDelivery
import dev.haibachvan.codexintellij.review.ReviewTarget
import java.util.concurrent.CompletableFuture

class ReviewCommandHandler(private val reviewController: ReviewController) {
    fun start(threadId: String): CompletableFuture<String> =
        reviewController.start(ReviewTarget(threadId), ReviewDelivery.PANEL)
}
