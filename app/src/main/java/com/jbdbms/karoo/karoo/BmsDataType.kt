package com.jbdbms.karoo.karoo

import com.jbdbms.karoo.data.BmsRepository
import com.jbdbms.karoo.protocol.BmsData
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Karoo ride-screen data field backed by the live [BmsRepository] state.
 *
 * The selected value is de-duplicated so the expensive value->Bundle->AIDL
 * serialization to the Karoo host only happens when the field actually changes,
 * and each active stream registers as a consumer so BLE polling can pause when
 * nothing is watching.
 */
class BmsDataType(
    extension: String,
    typeId: String,
    private val select: (BmsData) -> Double?,
) : DataTypeImpl(extension, typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        BmsRepository.addConsumer()
        val released = AtomicBoolean(false)

        val job = scope.launch {
            BmsRepository.data
                .map { data -> if (data.connected) select(data) else null }
                .distinctUntilChanged()
                .collect { value ->
                    if (value != null) {
                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(
                                    dataTypeId = dataTypeId,
                                    values = mapOf(DataType.Field.SINGLE to value),
                                ),
                            ),
                        )
                    } else {
                        emitter.onNext(StreamState.Searching)
                    }
                }
        }

        emitter.setCancellable {
            job.cancel()
            scope.cancel()
            if (released.compareAndSet(false, true)) {
                BmsRepository.removeConsumer()
            }
        }
    }
}
