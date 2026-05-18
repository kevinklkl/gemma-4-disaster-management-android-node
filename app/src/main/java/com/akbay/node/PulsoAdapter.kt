package com.akbay.node

import android.content.res.ColorStateList
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.akbay.node.databinding.ItemPulsoCardBinding
import com.akbay.node.databinding.ItemPulsoInnerRowBinding
import java.text.SimpleDateFormat
import java.util.*

class PulsoAdapter(
    private val onMarkDone: (msgId: String) -> Unit
) : RecyclerView.Adapter<PulsoAdapter.ViewHolder>() {

    data class PulsoItem(
        val name: String,
        val quantity: String,
        var isChecked: Boolean = false,
        var currentCount: String = "0"
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
            val loc = item.location.trim()
            tvLocation.text = if (loc.isEmpty() || loc.lowercase() == "null") "Unknown" else loc
            tvPeopleCount.text = "${item.persons} people"
            tvSource.text = item.type

            val (mainColor, softColor) = getUrgencyColors(item.urgency, ctx)
            
            urgencyBar.setBackgroundColor(mainColor)

            tvUrgencyBadge.backgroundTintList = ColorStateList.valueOf(softColor)
            tvUrgencyBadge.setTextColor(mainColor)
            tvUrgencyBadge.text = item.urgency.uppercase()

            // Items
            itemsContainer.removeAllViews()
            item.itemsList.forEach { pulsoItem ->
                val row = ItemPulsoInnerRowBinding.inflate(LayoutInflater.from(ctx), itemsContainer, false)
                row.tvItemName.text = pulsoItem.name
                row.tvTargetQty.text = pulsoItem.quantity
                
                // Set initial state
                row.etItemQty.tag = "binding"
                val display = if (pulsoItem.currentCount == "0") "" else pulsoItem.currentCount
                row.etItemQty.setText(display)
                row.etItemQty.tag = null
                
                updateItemStyle(row, pulsoItem.isChecked)

                row.checkBox.isChecked = pulsoItem.isChecked
                row.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    pulsoItem.isChecked = isChecked
                    updateItemStyle(row, isChecked)
                    updateProgress(holder, item)
                }
                
                // Allow unclicking by tapping the green check or the name
                val toggleCheck = View.OnClickListener {
                    row.checkBox.toggle()
                }
                row.ivChecked.setOnClickListener(toggleCheck)
                row.tvItemName.setOnClickListener(toggleCheck)

                row.etItemQty.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (row.etItemQty.tag == "binding") return
                        
                        val input = s?.toString() ?: ""
                        pulsoItem.currentCount = input.ifEmpty { "0" }
                        
                        val current = input.toIntOrNull() ?: 0
                        val targetNum = pulsoItem.quantity.split(" ").firstOrNull()?.toIntOrNull()
                        
                        if (targetNum == null) {
                            if (current > 0 && !row.checkBox.isChecked) {
                                row.checkBox.isChecked = true
                            }
                        } else {
                            if (current >= targetNum && !row.checkBox.isChecked) {
                                row.checkBox.isChecked = true
                            }
                        }

                        updateProgress(holder, item)
                    }
                })

                itemsContainer.addView(row.root)
            }

            updateProgress(holder, item)

            btnDispatch.setOnClickListener { onMarkDone(item.id) }
        }
    }

    private fun updateItemStyle(row: ItemPulsoInnerRowBinding, isChecked: Boolean) {
        if (isChecked) {
            row.checkBox.visibility = View.GONE
            row.ivChecked.visibility = View.VISIBLE
            row.tvItemName.paintFlags = row.tvItemName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            row.tvItemName.alpha = 0.5f
        } else {
            row.checkBox.visibility = View.VISIBLE
            row.ivChecked.visibility = View.GONE
            row.tvItemName.paintFlags = row.tvItemName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            row.tvItemName.alpha = 1.0f
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

        if (percent == 100) {
            holder.binding.btnDispatch.isEnabled = true
            holder.binding.progressBar.progressTintList = ContextCompat.getColorStateList(holder.itemView.context, R.color.akbay_damay)
            holder.binding.btnDispatch.text = "ready for dispatch"
            holder.binding.btnDispatch.alpha = 1.0f
        } else {
            holder.binding.btnDispatch.isEnabled = checked > 0
            holder.binding.btnDispatch.alpha = if (checked > 0) 1.0f else 0.5f
            holder.binding.progressBar.progressTintList = ContextCompat.getColorStateList(holder.itemView.context, R.color.akbay_primary)
            holder.binding.btnDispatch.text = "pack items to dispatch"
        }
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

    private fun getUrgencyColors(urgency: String, ctx: android.content.Context): Pair<Int, Int> {
        val (mainRes, softRes) = when (urgency.lowercase()) {
            "critical" -> 
                Pair(R.color.akbay_tabang, R.color.akbay_tabang_soft)
            "high", "urgent" -> 
                Pair(R.color.akbay_signal, R.color.akbay_signal_soft)
            "medium" -> 
                Pair(R.color.akbay_araw, R.color.akbay_araw_soft)
            "low" -> 
                Pair(R.color.akbay_ash, R.color.akbay_divider) // divider as soft ash
            "covered", "resolved", "done" -> 
                Pair(R.color.akbay_damay, R.color.akbay_damay_soft)
            else -> 
                Pair(R.color.akbay_ash, R.color.akbay_divider)
        }
        return Pair(
            ContextCompat.getColor(ctx, mainRes),
            ContextCompat.getColor(ctx, softRes)
        )
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
