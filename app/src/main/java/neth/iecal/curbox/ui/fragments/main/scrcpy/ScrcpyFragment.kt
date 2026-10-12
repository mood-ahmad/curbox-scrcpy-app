package neth.iecal.curbox.ui.fragments.main.scrcpy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentScrcpyBinding
import neth.iecal.curbox.remotecontrol.RemoteControlManager
import neth.iecal.curbox.remotecontrol.RemoteControlState

class ScrcpyFragment : Fragment() {

    private var _binding: FragmentScrcpyBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScrcpyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            RemoteControlManager.state.collectLatest { state ->
                updateUi(state)
            }
        }

        binding.buttonStartStop.setOnClickListener {
            val currentState = RemoteControlManager.state.value
            if (currentState is RemoteControlState.Ready || currentState is RemoteControlState.Starting) {
                RemoteControlManager.stop()
            } else {
                RemoteControlManager.start(requireContext())
            }
        }

        binding.buttonRefresh.setOnClickListener {
            RemoteControlManager.refresh()
        }
    }

    private fun updateUi(state: RemoteControlState) {
        when (state) {
            is RemoteControlState.Idle -> {
                binding.textStatus.text = getString(R.string.scrcpy_status_stopped)
                binding.textLink.visibility = View.GONE
                binding.buttonStartStop.text = getString(R.string.scrcpy_button_start)
                binding.buttonStartStop.isEnabled = true
                binding.buttonRefresh.isEnabled = false
            }
            is RemoteControlState.Starting -> {
                binding.textStatus.text = getString(R.string.scrcpy_status_starting)
                binding.textLink.visibility = View.GONE
                binding.buttonStartStop.text = getString(R.string.scrcpy_button_stop)
                binding.buttonStartStop.isEnabled = true
                binding.buttonRefresh.isEnabled = false
            }
            is RemoteControlState.Ready -> {
                binding.textStatus.text = getString(R.string.scrcpy_status_running)
                binding.textLink.text = state.link
                binding.textLink.visibility = View.VISIBLE
                binding.buttonStartStop.text = getString(R.string.scrcpy_button_stop)
                binding.buttonStartStop.isEnabled = true
                binding.buttonRefresh.isEnabled = true
            }
            is RemoteControlState.Error -> {
                binding.textStatus.text = state.message
                binding.textLink.visibility = View.GONE
                binding.buttonStartStop.text = getString(R.string.scrcpy_button_retry)
                binding.buttonStartStop.isEnabled = true
                binding.buttonRefresh.isEnabled = false
            }
            is RemoteControlState.Stopping -> {
                binding.textStatus.text = getString(R.string.scrcpy_status_stopping)
                binding.buttonStartStop.isEnabled = false
                binding.buttonRefresh.isEnabled = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
