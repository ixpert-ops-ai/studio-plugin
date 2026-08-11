
val text = "주문 결제 전 무통장 입금 기한 연장"
val words = text.lowercase().replace(Regex("[^a-z0-9\\uAC00-\\uD7A3\\s]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
val qTokens = mutableListOf<String>()
for (w in words) { if (w.length >= 2) qTokens.addAll(w.windowed(2)) else qTokens.add(w) }

val gtText = "OrderProcessBaseRequest src/main/java/com/samsungcardmall/api/bo/app/dto/request/or/order/OrderProcessBaseRequest.java PRESENTATION"
val gtWords = gtText.lowercase().replace(Regex("[^a-z0-9\\uAC00-\\uD7A3\\s]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
val gtTokens = mutableListOf<String>()
for (w in gtWords) { if (w.length >= 2) gtTokens.addAll(w.windowed(2)) else gtTokens.add(w) }

val intersection = qTokens.filter { gtTokens.contains(it) }
println("Intersection: " + intersection)
println("Intersection HEX: " + intersection.map { it.toByteArray().joinToString(" ") { String.format("%02X", it) } })

