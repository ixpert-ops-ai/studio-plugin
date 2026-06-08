$(document).ready(function() {
    function loadData() {
        $.ajax({
            url: "/api/survey/regist.do",
            type: "POST",
            data: { surveyTitle: $("#title").val() },
            success: function(result) {
                console.log(result.data);
            }
        });

        fetch("/api/user/list.json").then(r => r.json());
        
        // Let's create lots of hints to test the circuit breaker (100 max)
        // Here we just write many $.ajax to simulate a large legacy file
        $.ajax({url: "/test/01.do"});
        $.ajax({url: "/test/02.do"});
        $.ajax({url: "/test/03.do"});
        $.ajax({url: "/test/04.do"});
        $.ajax({url: "/test/05.do"});
        $.ajax({url: "/test/06.do"});
        $.ajax({url: "/test/07.do"});
        $.ajax({url: "/test/08.do"});
        $.ajax({url: "/test/09.do"});
        $.ajax({url: "/test/10.do"});
        $.ajax({url: "/test/11.do"});
        $.ajax({url: "/test/12.do"});
        $.ajax({url: "/test/13.do"});
        $.ajax({url: "/test/14.do"});
        $.ajax({url: "/test/15.do"});
        $.ajax({url: "/test/16.do"});
        $.ajax({url: "/test/17.do"});
        $.ajax({url: "/test/18.do"});
        $.ajax({url: "/test/19.do"});
        $.ajax({url: "/test/20.do"});
        $.ajax({url: "/test/21.do"}); // Over 20 limit per category check
    }
});
