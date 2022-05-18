import React from 'react';
import {connect} from 'react-redux';

import {Button} from 'primereact/button';
import {Dialog} from 'primereact/dialog';

/**
 * Dialog for uploading a file or directory.
 */
class UploadDialog extends React.Component {
  render() {
    return (
      <Dialog
        header={'Upload file/directory'}
        modal={true}
        visible={this.props.visible}
        onHide={this.props.onHide}
        footer={
          <div>
            <Button label="Upload" onClick={this.handleClick} />
            <Button label="Cancel" className="p-button-secondary" onClick={this.props.onHide} />
          </div>
        }
      >
        Choose a file or upload a whole directory
      </Dialog>
    );
  }

  handleClick = () => {
    this.props.onHide();
  };
}

export default connect()(UploadDialog);
