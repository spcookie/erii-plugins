package uesugi.plugin

internal fun <T> selectContiguousReferences(
    recordsNewestFirst: List<T>,
    maxImages: Int = 3,
    isImage: (T) -> Boolean
): List<T> {
    val latest = recordsNewestFirst.firstOrNull() ?: return emptyList()
    val candidates = if (isImage(latest)) recordsNewestFirst else recordsNewestFirst.drop(1)

    return candidates
        .takeWhile(isImage)
        .take(maxImages)
        .asReversed()
}
