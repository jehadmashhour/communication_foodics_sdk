package com.foodics.crosscommunicationlibrary.bluetooth

import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger

internal expect fun createBluetoothChannel(logger: CommunicationLogger?): CommunicationChannel
