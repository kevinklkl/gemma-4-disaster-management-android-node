package com.bayanihan.node

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bayanihan.node.databinding.ItemPulsoCardBinding
import com.bayanihan.node.databinding.ItemPulsoInnerRowBinding
import java.text.SimpleDateFormat
import java.util.*

class PulsoAdapter(
    private val onMarkDone: (msgId: String) -> Unit
) : RecyclerView.Adapter<PulsoAdapter.ViewHolder>() {

    data class PulsoItem(
        val name: String,
        val quantity: String,
        var isChecked: Boolean = false
    )

    data class PulsoMessage(
        val id: String,
        val source: String,
        val content: String,
        val time: String,
        val type: String,
        val urgency: String,
        val location: String,
        val persons: Int,
        val itemsList: List<PulsoItem>,
        val medical: String?
    )

    private val items = mutableListOf<PulsoMessage>()

    inner class ViewHolder(val binding: ItemPulsoCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPulsoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        with(holder.binding) {
            tvOrderId.text = "ORD-${item.id.takeLast(4).uppercase()}"
            tvLastUpdated.text = formatTimeAgo(item.time)
            tvLocation.text = item.location.ifEmpty { "Unknown" }
            tvPeopleCount.text = "${item.persons} people"
            tvSource.text = item.type

            val color = urgencyColor(item.urgency, ctx)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(color)
            }
            tvUrgencyBadge.background = bg
            tvUrgencyBadge.text = item.urgency.uppercase()

            // Items
            itemsContainer.removeAllViews()
            item.itemsList.forEach { pulsoItem ->
                val row = ItemPulsoInnerRowBinding.inflate(LayoutInflater.from(ctx), itemsContainer, false)
                row.tvItemName.text = pulsoItem.name
                row.tvItemQty.text = pulsoItem.quantity
                row.checkBox.isChecked = pulsoItem.isChecked
                row.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    pulsoItem.isChecked = isChecked
                    updateProgress(holder, item)
                }
                itemsContainer.addView(row.root)
            }

            updateProgress(holder, item)

            btnDispatch.setOnClickListener { onMarkDone(item.id) }
        }
    }

    private fun updateProgress(holder: ViewHolder, item: PulsoMessage) {
        val total = item.itemsList.size
        if (total == 0) {
            holder.binding.progressBar.progress = 0
            holder.binding.tvProgressPercent.text = "0% +?"
            return
        }
        val checked = item.itemsList.count { it.isChecked }
        val percent = (checked.toFloat() / total * 100).toInt()
        holder.binding.progressBar.progress = percent
        holder.binding.tvProgressPercent.text = "$percent% +?"
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<PulsoMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeById(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    private fun urgencyColor(urgency: String, ctx: android.content.Context): Int {
        val resId = when (urgency.lowercase()) {
            "critical"       -> R.color.akbay_tabang
            "high", "urgent" -> R.color.akbay_signal
            "low"            -> R.color.akbay_damay
            else             -> R.color.akbay_ash
        }
        return ContextCompat.getColor(ctx, resId)
    }

    private fun formatTimeAgo(iso: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(iso.take(19)) ?: return "—"
            val diffMs = System.currentTimeMillis() - date.time
            val diffSec = diffMs / 1000
            val diffMin = diffSec / 60
            val diffHr = diffMin / 60
            val diffDay = diffHr / 24

            when {
                diffDay > 0 -> "${diffDay}d ${diffHr % 24}h ago"
                diffHr > 0 -> "${diffHr}h ${diffMin % 60}m ago"
                diffMin > 0 -> "${diffMin}m ago"
                else -> "Just now"
            }
        } catch (_: Exception) {
            "Recently"
        }
    }
}
