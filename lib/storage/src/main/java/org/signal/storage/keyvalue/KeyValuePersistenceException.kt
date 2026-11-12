/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.storage.keyvalue

import java.io.IOException

/** A key-value batch did not receive an uninterrupted successful persistence acknowledgement. */
class KeyValuePersistenceException(cause: Throwable) : IOException("Key-value persistence was not acknowledged", cause)
