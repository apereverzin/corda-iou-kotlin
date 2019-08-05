package com.iou.flows

import co.paralleluniverse.fibers.Suspendable
import com.iou.contracts.IOUContract
import com.iou.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object IssueIOUFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val iouValue: Int,
                    val otherParty: Party) : FlowLogic<SignedTransaction>() {

        @Suspendable
        override fun call(): SignedTransaction {
            // Initiator flow logic goes here.
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 1.
            progressTracker.currentStep = ISSUING_IOU
            val iouState = IOUState(iouValue,
                    0,
                    serviceHub.myInfo.legalIdentities.first(),
                    otherParty)
            val txCommand = Command(IOUContract.Commands.CreateIOU(),
                    iouState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(iouState, IOUContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_IOU
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_IOU
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGNATURES
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartySession = initiateFlow(otherParty)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx,
                    setOf(otherPartySession),
                    GATHERING_SIGNATURES.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALIZING_IOU
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALIZING_IOU.childProgressTracker()))
        }

        companion object {
            object ISSUING_IOU : Step("Issuing IOU")
            object VERIFYING_IOU : Step("Verifying IOU")
            object SIGNING_IOU : Step("Signing IOU with private key")
            object GATHERING_SIGNATURES : Step("Gathering conterparies' signatures") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALIZING_IOU : Step("Finalizing IOU") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }
        }

        override val progressTracker = ProgressTracker(
                ISSUING_IOU,
                VERIFYING_IOU,
                SIGNING_IOU,
                GATHERING_SIGNATURES,
                FINALIZING_IOU
        )
    }

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an IOU transaction." using (output is IOUState)
                    val iou = output as IOUState
                    "I won't accept IOUs with a value over 100." using (iou.value <= 100)
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
        }
    }
}