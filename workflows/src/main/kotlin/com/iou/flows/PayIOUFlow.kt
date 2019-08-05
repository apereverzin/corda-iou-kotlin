package com.iou.flows

import co.paralleluniverse.fibers.Suspendable
import com.iou.contracts.IOUContract
import com.iou.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

object PayIOUFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val paidValue: Int,
                    val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

        @Suspendable
        override fun call(): SignedTransaction {
            // Initiator flow logic goes here.
            // Retrieving the input from the vault.
            progressTracker.currentStep = STEP11
            val inputCriteria =
                    QueryCriteria.LinearStateQueryCriteria(status = Vault.StateStatus.UNCONSUMED,
                            linearId = listOf(linearId))
            progressTracker.currentStep = STEP12
            val inputStateAndRef =
                    serviceHub.vaultService.queryBy<IOUState>(inputCriteria).states.single()
            progressTracker.currentStep = STEP13
            val input = inputStateAndRef.state.data

            requireThat {
                "Payer must be borrower." using (input.borrower == serviceHub.myInfo.legalIdentities.first())
            }

            val notary = inputStateAndRef.state.notary

            // Stage 1.
            progressTracker.currentStep = PAYING_IOU
            val iouState = IOUState(input.value,
                    input.paid + paidValue,
                    input.lender,
                    input.borrower)
            val txCommand = Command(IOUContract.Commands.PayIOU(),
                    iouState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(inputStateAndRef)
                    .addOutputState(iouState, IOUContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_PAYMENT
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_PAYMENT
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGNATURES
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartySession = initiateFlow(input.lender)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx,
                    setOf(otherPartySession),
                    GATHERING_SIGNATURES.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALIZING_PAYMENT
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALIZING_PAYMENT.childProgressTracker()))
        }

        companion object {
            object STEP11 : Step("Step 11")
            object STEP12 : Step("Step 12")
            object STEP13 : Step("Step 13")
            object PAYING_IOU : Step("Paying IOU")
            object VERIFYING_PAYMENT : Step("Verifying IOU")
            object SIGNING_PAYMENT : Step("Signing IOU with private key")
            object GATHERING_SIGNATURES : Step("Gathering conterparies' signatures") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALIZING_PAYMENT : Step("Finalizing IOU") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }
        }

        override val progressTracker = ProgressTracker(
                STEP11,
                STEP12,
                STEP13,
                PAYING_IOU,
                VERIFYING_PAYMENT,
                SIGNING_PAYMENT,
                GATHERING_SIGNATURES,
                FINALIZING_PAYMENT
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
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
        }
    }
}
