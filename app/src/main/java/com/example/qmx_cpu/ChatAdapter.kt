package com.example.qmx_cpu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onSpeakClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.tvMessage.text = msg.text
        } else if (holder is AssistantViewHolder) {
            holder.tvMessage.text = msg.text
            // Show speak button only for completed (non-streaming) assistant messages
            if (msg.isStreaming) {
                holder.btnSpeak.visibility = View.GONE
            } else {
                holder.btnSpeak.visibility = View.VISIBLE
                holder.btnSpeak.setOnClickListener {
                    onSpeakClick?.invoke(msg.text)
                }
            }
        }
    }

    /**
     * Partial bind with payloads — called when notifyItemChanged(pos, payload) is used.
     * Only updates the text content without rebinding the entire ViewHolder,
     * avoiding layout thrashing that causes visible jitter during token streaming.
     */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads[0] == "text_update") {
            // Fast path: only update text, skip full rebind
            val msg = messages[position]
            if (holder is AssistantViewHolder) {
                holder.tvMessage.text = msg.text
            } else if (holder is UserViewHolder) {
                holder.tvMessage.text = msg.text
            }
        } else {
            // Full rebind (no payload or unknown payload)
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvUserMessage)
    }

    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvAssistantMessage)
        val btnSpeak: ImageButton = itemView.findViewById(R.id.btnSpeak)
    }
}
