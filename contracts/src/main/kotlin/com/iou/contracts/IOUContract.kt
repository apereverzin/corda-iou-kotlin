package com.iou.contracts

import com.iou.state.IOUState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

// ************
// * Contract *
// ************
class IOUContract : Contract {
    companion object {
        // Used to identify our contract when building a transaction.
        const val ID = "com.iou.contracts.IOUContract"
    }

    // A transaction is valid if the verify() function of the contract of all the transaction's input and output state
    // does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        val cmd = tx.commands.requireSingleCommand<Commands>()

        when (cmd.value) {
            is Commands.CreateIOU -> requireThat {
                // Generic constraints around the IOU transaction.
                "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
                "Only one output state should be created." using (tx.outputs.size == 1)
                val out = tx.outputsOfType<IOUState>().single()
                "The lender and the borrower cannot be the same entity." using (out.lender != out.borrower)
                "All of the participants must be signers." using (cmd.signers.containsAll(out.participants.map { it.owningKey }))

                // IOU-specific constraints.
                "The IOU's value must be non-negative." using (out.value > 0)
            }
            is Commands.PayIOU -> requireThat {
                // Generic constraints around the IOU transaction.
//                "No inputs should be consumed when issuing a payment." using (tx.inputs.isEmpty())
                "Only one input state should be consumed but ${tx.inputs.size} found." using (tx.inputs.size == 1)
                "Only one output state should be created but ${tx.outputs.size} found." using (tx.outputs.size == 1)

                // IOU-specific constraints.
                val out = tx.outputsOfType<IOUState>().single()
                "Total paid should not exceed borrowed amount." using (out.paid <= out.value)
            }
        }
    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class CreateIOU : Commands
        class PayIOU : Commands
    }
}
