$(document).ready(function() {

	$('form').on('submit', function(event) {

		$.ajax({
			data : {
				name : $('#name').val(),
				address : $('#address').val(),
                                port : $('#port').val(),
                                authgroup : $('#authgroup').val(),
                                devtype : $('#devtype').val()
			},
			type : 'POST',
			url : '/devices/add-device'
		})
		.done(function(data) {

			if (data.error) {
				$('#errorAlert').text(data.error).show();
				$('#successAlert').hide();
			}
			else {
				$('#successAlert').text(data.name).show();
				$('#errorAlert').hide();
			}

		});

		event.preventDefault();
	});
});
