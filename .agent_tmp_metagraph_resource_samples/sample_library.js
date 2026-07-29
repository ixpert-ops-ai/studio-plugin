function dateUtilsFormat(date) {
    return date.getFullYear() + "-" + (date.getMonth() + 1);
}

function stringUtilsIsEmpty(str) {
    return str === null || str === "";
}

// No $.ajax, no fetch, no document.getElementById
// Should be classified as UNKNOWN and skipped.
